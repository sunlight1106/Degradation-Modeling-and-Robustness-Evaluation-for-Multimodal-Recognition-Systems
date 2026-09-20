package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@Service
@ConditionalOnProperty(prefix = "app.storage", name = "mode", havingValue = "filesystem", matchIfMissing = true)
public class LocalObjectStorageService implements ObjectStorageService {
    private final Path root;

    public LocalObjectStorageService(@Value("${app.storage.root:./data/uploads}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @PostConstruct
    void initialize() throws IOException { Files.createDirectories(root); }

    @Override
    public void put(String key, byte[] content, String contentType) {
        Path path = resolve(key);
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException exception) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "OBJECT_STORE_WRITE_FAILED", "对象存储写入失败");
        }
    }

    @Override
    public byte[] get(String key) {
        try { return Files.readAllBytes(resolve(key)); }
        catch (IOException exception) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "FILE_CONTENT_MISSING", "对象内容不存在");
        }
    }

    @Override
    public boolean exists(String key) { return Files.isRegularFile(resolve(key)); }

    @Override
    public void delete(String key) {
        try { Files.deleteIfExists(resolve(key)); }
        catch (IOException ignored) { }
    }

    @Override
    public String backendName() { return "filesystem"; }

    private Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_OBJECT_KEY", "对象键不合法");
        }
        return path;
    }
}
