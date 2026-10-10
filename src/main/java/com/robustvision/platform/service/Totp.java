package com.robustvision.platform.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 6238, SHA-1 / 6 digits / 30 seconds. Secrets are never logged. */
public final class Totp {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private Totp() {}
    public static String secret() {
        byte[] bytes = new byte[20]; new SecureRandom().nextBytes(bytes);
        StringBuilder out = new StringBuilder(); int bits = 0, value = 0;
        for (byte b : bytes) { value = (value << 8) | (b & 255); bits += 8;
            while (bits >= 5) { bits -= 5; out.append(ALPHABET.charAt((value >>> bits) & 31)); }
        } return out.toString();
    }
    public static String code(String secret, long counter) {
        try {
            byte[] key = new byte[secret.length() * 5 / 8]; int bits = 0, value = 0, index = 0;
            for (char c : secret.toCharArray()) {
                int n = ALPHABET.indexOf(c); if (n < 0) throw new IllegalArgumentException("Invalid secret");
                value = (value << 5) | n; bits += 5;
                if (bits >= 8) { bits -= 8; key[index++] = (byte) (value >>> bits); }
            }
            Mac mac = Mac.getInstance("HmacSHA1"); mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array()); int offset = hash[19] & 15;
            int binary = ((hash[offset] & 127) << 24) | ((hash[offset+1] & 255) << 16) | ((hash[offset+2] & 255) << 8) | (hash[offset+3] & 255);
            return String.format(java.util.Locale.ROOT, "%06d", binary % 1_000_000);
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    public static long match(String secret, String input, long current, long previous) {
        if (input == null || !input.matches("[0-9]{6}")) return -1;
        for (long c = current - 1; c <= current + 1; c++)
            if (c > previous && MessageDigest.isEqual(code(secret, c).getBytes(StandardCharsets.US_ASCII), input.getBytes(StandardCharsets.US_ASCII))) return c;
        return -1;
    }
}
