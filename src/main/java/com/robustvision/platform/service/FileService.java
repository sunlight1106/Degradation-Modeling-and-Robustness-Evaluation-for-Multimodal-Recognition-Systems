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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class FileService {
    private final FileAssetRepository fileRepository;
    private final CurrentUserService currentUserService;
    private final ObjectStorageService objectStorage;
    private final AntivirusService antivirusService;
    private final MediaProcessingService mediaProcessingService;
    private final Path legacyRoot;
    private final long maxSize;

    public FileService(FileAssetRepository fileRepository,
                       CurrentUserService currentUserService,
                       ObjectStorageService objectStorage,
                       AntivirusService antivirusService,
                       MediaProcessingService mediaProcessingService,
                       @Value("${app.storage.legacy-root:./data/uploads}") String legacyRoot,
                       @Value("${app.storage.max-size-bytes:20971520}") long maxSize) {
        this.fileRepository = fileRepository;
        this.currentUserService = currentUserService;
        this.objectStorage = objectStorage;
        this.antivirusService = antivirusService;
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
            DetectedType type = detectType(bytes);
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
            String extension = extension(original);
            String contentType = attachmentType(bytes, extension);
            AntivirusService.ScanResult scan = antivirusService.scan(bytes);
            String safeName = sanitizeName(original, extension.isBlank() ? "bin" : extension);
            String storedName = UUID.randomUUID() + (extension.isBlank() ? "" : "." + extension);
            String key = datedKey(storedName); objectStorage.put(key, bytes, contentType);
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

    private DetectedType detectType(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff)
            return new DetectedType("image/jpeg", "jpg");
        if (bytes.length >= 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4e
                && bytes[3] == 0x47 && bytes[4] == 0x0d && bytes[5] == 0x0a && bytes[6] == 0x1a && bytes[7] == 0x0a)
            return new DetectedType("image/png", "png");
        if (bytes.length >= 12 && ascii(bytes, 0, 4).equals("RIFF") && ascii(bytes, 8, 4).equals("WEBP"))
            return new DetectedType("image/webp", "webp");
        if (bytes.length >= 12 && ascii(bytes, 4, 4).equals("ftyp"))
            return new DetectedType("video/mp4", "mp4");
        if (bytes.length >= 4 && (bytes[0] & 0xff) == 0x1a && (bytes[1] & 0xff) == 0x45
                && (bytes[2] & 0xff) == 0xdf && (bytes[3] & 0xff) == 0xa3)
            return new DetectedType("video/webm", "webm");
        throw new BusinessException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FILE_TYPE",
                "仅支持真实的 JPEG、PNG、WEBP、MP4 或 WEBM 文件");
    }

    private String ascii(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, StandardCharsets.US_ASCII);
    }

    private String sanitizeName(String originalName, String extension) {
        String name = originalName == null ? "media." + extension : Path.of(originalName).getFileName().toString();
        name = name.replaceAll("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]", "_");
        if (name.length() > 180) name = name.substring(name.length() - 180);
        return name.isBlank() ? "media." + extension : name;
    }

    private String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot + 1 < name.length() ? name.substring(dot + 1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "") : "";
    }

    private String attachmentType(byte[] bytes, String extension) {
        try { return detectType(bytes).contentType(); }
        catch (BusinessException ignored) { }
        if (bytes.length >= 4 && ascii(bytes, 0, 4).equals("%PDF") && "pdf".equals(extension)) return "application/pdf";
        if (bytes.length >= 4 && bytes[0] == 0x50 && bytes[1] == 0x4b && List.of("docx", "xlsx", "pptx").contains(extension))
            return "application/vnd.openxmlformats-officedocument." + ("docx".equals(extension) ? "wordprocessingml.document" : "octet-stream");
        if (List.of("txt", "csv", "json", "md").contains(extension)) return "text/plain";
        throw new BusinessException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "ATTACHMENT_TYPE_UNSUPPORTED", "附件仅支持图片、视频、PDF、DOCX、TXT、CSV、JSON 或 Markdown");
    }

    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }

    private BufferedImage enhance(BufferedImage source) {
        BufferedImage output = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < source.getHeight(); y++) for (int x = 0; x < source.getWidth(); x++) {
            int argb = source.getRGB(x, y);
            int alpha = (argb >>> 24) & 0xff;
            int red = adjust((argb >>> 16) & 0xff);
            int green = adjust((argb >>> 8) & 0xff);
            int blue = adjust(argb & 0xff);
            output.setRGB(x, y, (alpha << 24) | (red << 16) | (green << 8) | blue);
        }
        return output;
    }

    private int adjust(int value) { return Math.max(0, Math.min(255, (int) ((value - 128) * 1.08 + 136))); }
    private String stripExtension(String filename) { int dot = filename.lastIndexOf('.'); return dot > 0 ? filename.substring(0, dot) : filename; }
    private record DetectedType(String contentType, String extension) {}
}
