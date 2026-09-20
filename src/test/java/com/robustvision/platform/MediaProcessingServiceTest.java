package com.robustvision.platform;

import com.robustvision.platform.service.MediaProcessingService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class MediaProcessingServiceTest {
    @Test
    void validVideoWithoutAudioStillProducesComparableMp4() throws Exception {
        Assumptions.assumeTrue(ffmpegAvailable(), "FFmpeg is not installed in this test environment");
        Path source = Files.createTempFile("personal-platform-video-", ".mp4");
        try {
            Process generated = new ProcessBuilder(
                    "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                    "-f", "lavfi", "-i", "color=c=black:s=64x64:d=0.25",
                    "-an", "-c:v", "libx264", "-pix_fmt", "yuv420p", source.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            assertThat(generated.waitFor(20, TimeUnit.SECONDS)).isTrue();
            assertThat(generated.exitValue()).isZero();

            MediaProcessingService.ProcessedMedia result = new MediaProcessingService("ffmpeg")
                    .denoiseVideoAudio(Files.readAllBytes(source));
            assertThat(result.bytes()).isNotEmpty();
            assertThat(result.processing()).contains("video stream preserved");
        } finally {
            Files.deleteIfExists(source);
        }
    }

    private boolean ffmpegAvailable() {
        try {
            Process process = new ProcessBuilder("ffmpeg", "-version")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }
}
