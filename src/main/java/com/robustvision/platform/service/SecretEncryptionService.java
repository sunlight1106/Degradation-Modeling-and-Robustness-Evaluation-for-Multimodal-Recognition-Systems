package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class SecretEncryptionService {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretEncryptionService(@Value("${app.credentials.master-key:development-credential-master-key-change-me}") String masterKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(masterKey.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(digest, "AES");
        } catch (Exception exception) { throw new IllegalStateException("无法初始化密钥加密器", exception); }
    }

    public String encrypt(String value) {
        try {
            byte[] iv = new byte[12]; random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(packed);
        } catch (Exception exception) { throw new IllegalStateException("密钥加密失败", exception); }
    }

    public String decrypt(String packedValue) {
        try {
            byte[] packed = Base64.getDecoder().decode(packedValue);
            if (packed.length < 29) throw new IllegalArgumentException("invalid encrypted payload");
            byte[] iv = java.util.Arrays.copyOfRange(packed, 0, 12);
            byte[] encrypted = java.util.Arrays.copyOfRange(packed, 12, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "CREDENTIAL_DECRYPT_FAILED", "模型密钥无法解密，请由管理员重新录入");
        }
    }
}
