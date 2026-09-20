package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.FileScanStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

@Service
public class AntivirusService {
    private final boolean enabled;
    private final boolean required;
    private final String host;
    private final int port;
    private final int connectTimeout;
    private final int readTimeout;

    public AntivirusService(
            @Value("${app.antivirus.enabled:false}") boolean enabled,
            @Value("${app.antivirus.required:true}") boolean required,
            @Value("${app.antivirus.host:localhost}") String host,
            @Value("${app.antivirus.port:3310}") int port,
            @Value("${app.antivirus.connect-timeout-ms:3000}") int connectTimeout,
            @Value("${app.antivirus.read-timeout-ms:30000}") int readTimeout) {
        this.enabled = enabled;
        this.required = required;
        this.host = host;
        this.port = port;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    public ScanResult scan(byte[] bytes) {
        if (!enabled) return new ScanResult(FileScanStatus.SKIPPED, "disabled");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), connectTimeout);
            socket.setSoTimeout(readTimeout);
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            int offset = 0;
            while (offset < bytes.length) {
                int size = Math.min(8192, bytes.length - offset);
                output.writeInt(size);
                output.write(bytes, offset, size);
                offset += size;
            }
            output.writeInt(0);
            output.flush();

            ByteArrayOutputStream response = new ByteArrayOutputStream();
            int value;
            while ((value = socket.getInputStream().read()) >= 0 && value != 0) response.write(value);
            String result = response.toString(StandardCharsets.UTF_8);
            if (result.contains(" FOUND")) {
                throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "FILE_INFECTED", "文件未通过病毒扫描，已拒绝保存");
            }
            if (!result.contains(" OK")) throw new IllegalStateException(result);
            return new ScanResult(FileScanStatus.CLEAN, "ClamAV clamd");
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            if (!required) return new ScanResult(FileScanStatus.SKIPPED, "ClamAV unavailable");
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "VIRUS_SCANNER_UNAVAILABLE", "病毒扫描服务尚未就绪，请稍后重试");
        }
    }

    public record ScanResult(FileScanStatus status, String engine) {}
}
