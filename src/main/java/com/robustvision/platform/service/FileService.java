package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.FileAssetEntity;
import com.robustvision.platform.domain.FileSource;
import com.robustvision.platform.domain.UserEntity;
import com.robustvision.platform.dto.ApiDtos;
import com.robustvision.platform.repository.FileAssetRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class FileService {
    private final FileAssetRepository fileRepository;
    private final CurrentUserService currentUserService;
    private final ObjectStorageService objectStorage;
    private final AntivirusService antivirusService;
    private final ContentInspectionService contentInspectionService;
    private final MediaProcessingService mediaProcessingService;
    private final Path legacyRoot;
    private final long maxSize;

    public FileService(FileAssetRepository fileRepository,
                       CurrentUserService currentUserService,
                       ObjectStorageService objectStorage,
                       AntivirusService antivirusService,
                       ContentInspectionService contentInspectionService,
                       MediaProcessingService mediaProcessingService,
                       @Value("${app.storage.legacy-root:./data/uploads}") String legacyRoot,
                       @Value("${app.storage.max-size-bytes:20971520}") long maxSize) {
        this.fileRepository = fileRepository;
        this.currentUserService = currentUserService;
        this.objectStorage = objectStorage;
        this.antivirusService = antivirusService;
        this.contentInspectionService = contentInspectionService;
        this.mediaProcessingService = mediaProcessingService;
        this.legacyRoot = Path.of(legacyRoot).toAbsolutePath().normalize();
        this.maxSize = maxSize;
    }

    @Transactional
    public ApiDtos.FileView upload(MultipartFile multipartFile) {
        UserEntity owner = currentUserService.requireCurrent();
        if (multipartFile.isEmpty()) throw new BusinessException(HttpStatus.BAD_REQUEST, "EMPTY_FILE", "请选择非空图片或视频");
        if (multipartFile.getSize() > maxSize) {
            throw new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "文件超过 20 MB 限制");
        }
        try {
            byte[] bytes = multipartFile.getBytes();
            validateReadSize(bytes);
            ContentInspectionService.InspectedType type = contentInspectionService.inspectMedia(
                    bytes, multipartFile.getOriginalFilename(), multipartFile.getContentType());
            AntivirusService.ScanResult scan = antivirusService.scan(bytes);
            String originalName = sanitizeName(multipartFile.getOriginalFilename(), type.extension());
            String storedName = UUID.randomUUID() + "." + type.extension();
            String key = datedKey(storedName);
            objectStorage.put(key, bytes, type.contentType());
            try {
                FileAssetEntity asset = new FileAssetEntity(
                        originalName, storedName, type.contentType(), bytes.length, sha256(bytes), key,
                        owner, FileSource.UPLOAD, scan.status(), scan.engine());
                return toView(fileRepository.save(asset));
            } catch (RuntimeException exception) {
                objectStorage.delete(key);
                throw exception;
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "FILE_STORE_FAILED", "文件保存失败");
        }
    }

    @Transactional(readOnly = true)
    public List<ApiDtos.FileView> listAccessible() {
        UserEntity current = currentUserService.requireCurrent();
        List<FileAssetEntity> files = currentUserService.hasPermission(current, "file:read:any")
                ? fileRepository.findAllByOrderByCreatedAtDesc()
                : fileRepository.findByOwnerIdOrderByCreatedAtDesc(current.getId());
        return files.stream().filter(file -> file.getSource() != FileSource.MESSAGE_ATTACHMENT).map(this::toView).toList();
    }

    @Transactional
    public FileAssetEntity storeMessageAttachment(MultipartFile multipartFile, UserEntity owner) {
        if (multipartFile == null || multipartFile.isEmpty()) throw new BusinessException(HttpStatus.BAD_REQUEST, "EMPTY_ATTACHMENT", "附件不能为空");
        if (multipartFile.getSize() > maxSize) throw new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE, "ATTACHMENT_TOO_LARGE", "单个附件不能超过 20 MB");
        try {
            byte[] bytes = multipartFile.getBytes();
            String original = multipartFile.getOriginalFilename() == null ? "attachment.bin" : multipartFile.getOriginalFilename();
            validateReadSize(bytes);
            ContentInspectionService.InspectedType type = contentInspectionService.inspectAttachment(bytes, original, multipartFile.getContentType());
            String extension = type.extension();
            String contentType = type.contentType();
            AntivirusService.ScanResult scan = antivirusService.scan(bytes);
            String safeName = sanitizeName(original, extension.isBlank() ? "bin" : extension);
            String storedName = UUID.randomUUID() + (extension.isBlank() ? "" : "." + extension);
            String key = datedKey(storedName); objectStorage.put(key, bytes, contentType);
            if(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive())
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
                    @Override public void afterCompletion(int status){if(status==STATUS_ROLLED_BACK)objectStorage.delete(key);}
                });
            try {
                return fileRepository.save(new FileAssetEntity(safeName, storedName, contentType, bytes.length, sha256(bytes), key,
                        owner, FileSource.MESSAGE_ATTACHMENT, scan.status(), scan.engine()));
            } catch (RuntimeException exception) { objectStorage.delete(key); throw exception; }
        } catch (BusinessException exception) { throw exception; }
        catch (IOException exception) { throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "ATTACHMENT_STORE_FAILED", "附件保存失败"); }
    }

    @Transactional(readOnly = true)
    public FileAssetEntity requireAccessible(String id) {
        FileAssetEntity asset = fileRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "文件不存在"));
        if (asset.getSource() == FileSource.MESSAGE_ATTACHMENT)
            throw new BusinessException(HttpStatus.FORBIDDEN, "MESSAGE_ATTACHMENT_ROUTE_REQUIRED", "请通过所属消息下载附件");
        UserEntity current = currentUserService.requireCurrent();
        boolean owner = asset.getOwner().getId().equals(current.getId());
        if (!owner && !currentUserService.hasPermission(current, "file:read:any")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FILE_ACCESS_DENIED", "无权访问此文件");
        }
        return asset;
    }

    @Transactional
    public FileAssetEntity createEnhancedCopy(FileAssetEntity source, UserEntity owner) {
        byte[] input = readBytes(source);
        if (source.getContentType().startsWith("video/")) {
            MediaProcessingService.ProcessedMedia processed = mediaProcessingService.denoiseVideoAudio(input);
            return storeDerived(source, owner, processed.bytes(), "video/mp4", "mp4", "denoised-");
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(input));
            if (image == null) throw new IOException("unsupported image");
            BufferedImage enhanced = enhance(image);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(enhanced, "png", output)) throw new IOException("No PNG writer available");
            return storeDerived(source, owner, output.toByteArray(), "image/png", "png", "enhanced-");
        } catch (IOException exception) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "ENHANCEMENT_FAILED", "生成对比文件失败");
        }
    }

    public byte[] readBytes(FileAssetEntity asset) {
        if (objectStorage.exists(asset.getStoragePath())) return objectStorage.get(asset.getStoragePath());
        Path legacyPath = legacyRoot.resolve(asset.getStoragePath()).normalize();
        if (legacyPath.startsWith(legacyRoot) && Files.isRegularFile(legacyPath)) {
            try {
                byte[] content = Files.readAllBytes(legacyPath);
                objectStorage.put(asset.getStoragePath(), content, asset.getContentType());
                return content;
            } catch (IOException ignored) { }
        }
        throw new BusinessException(HttpStatus.NOT_FOUND, "FILE_CONTENT_MISSING", "对象内容已丢失");
    }

    public Resource asResource(FileAssetEntity asset) {
        byte[] content = readBytes(asset);
        return new ByteArrayResource(content) {
            @Override public String getFilename() { return asset.getOriginalName(); }
        };
    }

    public ApiDtos.FileView toView(FileAssetEntity asset) {
        String base = "/api/v1/files/" + asset.getId();
        return new ApiDtos.FileView(
                asset.getId(), asset.getOriginalName(), asset.getContentType(), asset.getSizeBytes(), asset.getSha256(),
                asset.getSource(), asset.getScanStatus(), asset.getScanEngine(), objectStorage.backendName(),
                asset.getOwner().getDisplayName(), asset.getCreatedAt(), base + "/content", base + "/download");
    }

    private FileAssetEntity storeDerived(FileAssetEntity source, UserEntity owner, byte[] result,
                                         String contentType, String extension, String prefix) {
        contentInspectionService.inspectMedia(result, "derived." + extension, contentType);
        AntivirusService.ScanResult scan = antivirusService.scan(result);
        String storedName = UUID.randomUUID() + "." + extension;
        String key = datedKey(storedName);
        objectStorage.put(key, result, contentType);
        FileAssetEntity asset = new FileAssetEntity(
                prefix + stripExtension(source.getOriginalName()) + "." + extension,
                storedName, contentType, result.length, sha256(result), key, owner, FileSource.ENHANCED,
                scan.status(), scan.engine());
        return fileRepository.save(asset);
    }

    private String datedKey(String storedName) {
        LocalDate now = LocalDate.now(ZoneOffset.UTC);
        return "%04d/%02d/%02d/%s".formatted(now.getYear(), now.getMonthValue(), now.getDayOfMonth(), storedName);
    }

    private void validateReadSize(byte[] bytes) {
        if (bytes.length == 0) throw new BusinessException(HttpStatus.BAD_REQUEST, "EMPTY_FILE", "文件不能为空");
        if (bytes.length > maxSize) throw new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "文件超过大小限制");
    }

    private String sanitizeName(String originalName, String extension) {
        String name = originalName == null ? "media." + extension : originalName.replace('\\', '/').substring(originalName.replace('\\', '/').lastIndexOf('/') + 1);
        name = name.replaceAll("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]", "_");
        if (name.length() > 180) name = name.substring(name.length() - 180);
        return name.isBlank() ? "media." + extension : name;
    }

    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }

    private static final int[] ENHANCEMENT_LOOKUP = new int[256];
    static {
        for (int value = 0; value < ENHANCEMENT_LOOKUP.length; value++) {
            ENHANCEMENT_LOOKUP[value] = Math.max(0, Math.min(255, (int) ((value - 128) * 1.08 + 136)));
        }
    }

    static BufferedImage enhance(BufferedImage source) {
        int width = source.getWidth();
        BufferedImage output = new BufferedImage(width, source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        // Read through getRGB to retain color-model, premultiplication and subimage semantics.
        // One reusable scanline bounds temporary memory; the new output raster is contiguous ARGB.
        int[] row = new int[width];
        int[] pixels = ((java.awt.image.DataBufferInt) output.getRaster().getDataBuffer()).getData();
        for (int y = 0; y < source.getHeight(); y++) {
            source.getRGB(0, y, width, 1, row, 0, width);
            int offset = y * width;
            for (int x = 0; x < width; x++) {
                int argb = row[x];
                pixels[offset + x] = (argb & 0xff000000)
                        | (ENHANCEMENT_LOOKUP[(argb >>> 16) & 0xff] << 16)
                        | (ENHANCEMENT_LOOKUP[(argb >>> 8) & 0xff] << 8)
                        | ENHANCEMENT_LOOKUP[argb & 0xff];
            }
        }
        return output;
    }

    private String stripExtension(String filename) { int dot = filename.lastIndexOf('.'); return dot > 0 ? filename.substring(0, dot) : filename; }
}
