package com.robustvision.platform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Readiness fails closed for explicitly required external upload dependencies. */
@Component("stackDependencies")
public class StackDependenciesHealthIndicator implements HealthIndicator {
    private final boolean minio;
    private final boolean antivirus;
    private final String endpoint;
    private final String clamHost;
    private final int clamPort;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public StackDependenciesHealthIndicator(
            @Value("${app.storage.mode:filesystem}") String storageMode,
            @Value("${app.storage.s3.endpoint:http://localhost:9000}") String endpoint,
            @Value("${app.antivirus.enabled:false}") boolean enabled,
            @Value("${app.antivirus.required:true}") boolean required,
            @Value("${app.antivirus.host:localhost}") String clamHost,
            @Value("${app.antivirus.port:3310}") int clamPort) {
        this.minio = "minio".equalsIgnoreCase(storageMode);
        this.antivirus = enabled && required;
        this.endpoint = endpoint.replaceAll("/+$", "");
        this.clamHost = clamHost;
        this.clamPort = clamPort;
    }

    @Override public Health health() {
        try {
            if (minio) {
                var request = HttpRequest.newBuilder(URI.create(endpoint + "/minio/health/ready"))
                        .timeout(Duration.ofSeconds(2)).GET().build();
                if (http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() != 200)
                    return Health.down().withDetail("component", "objectStorage").build();
            }
            if (antivirus) {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(clamHost, clamPort), 2000);
                    socket.setSoTimeout(2000);
                    socket.getOutputStream().write("zPING\0".getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    byte[] reply = socket.getInputStream().readNBytes(5);
                    if (!"PONG\0".equals(new String(reply, StandardCharsets.US_ASCII)))
                        return Health.down().withDetail("component", "antivirus").build();
                }
            }
            return Health.up().build();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Health.down().build();
        } catch (Exception unavailable) {
            return Health.down().build();
        }
    }
}
