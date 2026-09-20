package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Service
public class MediaProcessingService {
    private final String ffmpegBinary;

    public MediaProcessingService(@Value("${app.media.ffmpeg-binary:ffmpeg}") String ffmpegBinary) {
        this.ffmpegBinary = ffmpegBinary;
    }

    public ProcessedMedia denoiseVideoAudio(byte[] input) {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("personal-media-");
            Path source = directory.resolve("input.mp4");
            Path target = directory.resolve("denoised.mp4");
            Files.write(source, input);

            boolean hasAudio = hasAudioTrack(source);
            ProcessBuilder builder = hasAudio
                    ? new ProcessBuilder(
                            ffmpegBinary, "-hide_banner", "-loglevel", "error", "-y",
                            "-i", source.toString(), "-map", "0:v:0", "-map", "0:a:0",
                            "-c:v", "copy",
                            "-af", "highpass=f=80,lowpass=f=12000,afftdn=nf=-25,loudnorm=I=-16:LRA=11:TP=-1.5",
                            "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart", target.toString())
                    : new ProcessBuilder(
                            ffmpegBinary, "-hide_banner", "-loglevel", "error", "-y",
                            "-i", source.toString(), "-map", "0:v:0", "-an", "-c:v", "copy",
                            "-movflags", "+faststart", target.toString());
            Process process = builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            boolean finished = process.waitFor(90, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new BusinessException(HttpStatus.REQUEST_TIMEOUT, "MEDIA_PROCESS_TIMEOUT", "视频降噪超时，请缩短视频后重试");
            }
            String processing = hasAudio
                    ? "video stream preserved; audio high-pass, low-pass, FFT denoise and loudness normalization"
                    : "video stream preserved; input contained no audio track";
            if (process.exitValue() != 0 || !Files.isRegularFile(target)) {
                throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "MEDIA_PROCESS_FAILED",
                        hasAudio ? "视频音轨无法处理，请确认文件编码有效" : "视频无法处理，请确认文件包含有效视频流");
            }
            return new ProcessedMedia(Files.readAllBytes(target), processing, Duration.ofSeconds(90));
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "FFMPEG_UNAVAILABLE", "视频处理组件不可用");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "MEDIA_PROCESS_INTERRUPTED", "视频处理被中断");
        } finally {
            if (directory != null) {
                try (var paths = Files.walk(directory)) {
                    paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
                    });
                } catch (IOException ignored) { }
            }
        }
    }

    private boolean hasAudioTrack(Path source) throws IOException, InterruptedException {
        Process probe = new ProcessBuilder(
                ffmpegBinary, "-hide_banner", "-loglevel", "error", "-i", source.toString(),
                "-map", "0:a:0", "-t", "0.01", "-f", "null", "-"
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        boolean finished = probe.waitFor(15, TimeUnit.SECONDS);
        if (!finished) {
            probe.destroyForcibly();
            throw new BusinessException(HttpStatus.REQUEST_TIMEOUT, "MEDIA_PROBE_TIMEOUT", "视频音轨检测超时");
        }
        return probe.exitValue() == 0;
    }

    public record ProcessedMedia(byte[] bytes, String processing, Duration timeoutBudget) {}
}
