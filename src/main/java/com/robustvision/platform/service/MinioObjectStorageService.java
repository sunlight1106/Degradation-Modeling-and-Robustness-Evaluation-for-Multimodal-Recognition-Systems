package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;

@Service
@ConditionalOnProperty(prefix = "app.storage", name = "mode", havingValue = "minio")
public class MinioObjectStorageService implements ObjectStorageService {
    private final MinioClient client;
    private final String bucket;

    public MinioObjectStorageService(
            @Value("${app.storage.s3.endpoint}") String endpoint,
            @Value("${app.storage.s3.access-key}") String accessKey,
            @Value("${app.storage.s3.secret-key}") String secretKey,
            @Value("${app.storage.s3.bucket}") String bucket) {
        this.client = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
        this.bucket = bucket;
    }

    @PostConstruct
    void initializeBucket() {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (Exception exception) {
            throw new IllegalStateException("无法初始化对象存储 bucket", exception);
        }
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        try (ByteArrayInputStream stream = new ByteArrayInputStream(content)) {
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                    .stream(stream, content.length, -1).contentType(contentType).build());
        } catch (Exception exception) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "OBJECT_STORE_WRITE_FAILED", "对象存储暂时不可用");
        }
    }

    @Override
    public byte[] get(String key) {
        try (var stream = client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            return stream.readAllBytes();
        } catch (Exception exception) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "FILE_CONTENT_MISSING", "对象内容不存在");
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            return true;
        } catch (Exception exception) { return false; }
    }

    @Override
    public void delete(String key) {
        try { client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build()); }
        catch (Exception ignored) { }
    }

    @Override
    public String backendName() { return "minio-s3"; }
}
