package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.FileScanStatus;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AntivirusServiceTest {
    @Test void onlyExactTerminatedCleanResponseIsClean() throws Exception {
        assertThat(scanWithResponse("stream: OK\0", true).status()).isEqualTo(FileScanStatus.CLEAN);
    }
    @Test void fakeClamdReportsHarmlessEicarFixtureAsInfected() throws Exception {
        // Official harmless antivirus test string; never execute it or download a malware sample.
        byte[] eicar = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*".getBytes(StandardCharsets.US_ASCII);
        assertThatThrownBy(() -> scanWithResponse("stream: Eicar-Test-Signature FOUND\0", true, eicar))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo("FILE_INFECTED"));
    }
    @Test void malformedUnavailableOversizeAndUnterminatedResponsesNeverBecomeClean() throws Exception {
        for (String response : new String[]{"stream: NOT OK\0", "stream: OK ERROR\0", "stream: OK", "", "x".repeat(4097) + "\0"}) {
            assertThat(scanWithResponse(response, false).status()).isEqualTo(FileScanStatus.SKIPPED);
            assertThatThrownBy(() -> scanWithResponse(response, true)).isInstanceOfSatisfying(BusinessException.class,
                    ex -> assertThat(ex.getCode()).isEqualTo("VIRUS_SCANNER_UNAVAILABLE"));
        }
    }
    @Test void disabledIsExplicitlySkipped() {
        assertThat(new AntivirusService(false, true, "localhost", 1, 100, 100).scan(new byte[]{1}).status()).isEqualTo(FileScanStatus.SKIPPED);
    }
    private AntivirusService.ScanResult scanWithResponse(String response, boolean required) throws Exception {
        return scanWithResponse(response, required, "benign".getBytes(StandardCharsets.US_ASCII));
    }
    private AntivirusService.ScanResult scanWithResponse(String response, boolean required, byte[] payload) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<Void> responder = CompletableFuture.runAsync(() -> {
                try (var socket = server.accept()) {
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    assertThat(input.readNBytes(10)).isEqualTo("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
                    var received = new java.io.ByteArrayOutputStream();
                    int size;
                    while ((size = input.readInt()) != 0) received.write(input.readNBytes(size));
                    assertThat(received.toByteArray()).isEqualTo(payload);
                    socket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                } catch (Exception ex) { throw new RuntimeException(ex); }
            });
            try { return new AntivirusService(true, required, server.getInetAddress().getHostAddress(), server.getLocalPort(), 1000, 1000).scan(payload); }
            finally { responder.get(5, TimeUnit.SECONDS); }
        }
    }
}
