package com.demo.demo.Service.voice;

import com.demo.demo.config.VoiceProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class AudioConverter implements AudioCodecService {

    private static final byte[] WECHAT_SILK_HEADER = "#!SILK_V3".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_AUDIO_BYTES = 50 * 1024 * 1024;

    private final VoiceProperties properties;
    private final CommandExecutor commandExecutor;

    @Autowired
    public AudioConverter(VoiceProperties properties) {
        this(properties, AudioConverter::executeCommand);
    }

    AudioConverter(VoiceProperties properties, CommandExecutor commandExecutor) {
        this.properties = properties;
        this.commandExecutor = commandExecutor;
    }

    @Override
    public byte[] silkToPcm(byte[] silkAudio) throws IOException {
        validateSilk(silkAudio);
        if (properties.getAudio().getSilkDecoderPath().isBlank()) {
            throw new IOException("SILK 解码器未配置");
        }

        Path workDir = Files.createTempDirectory("claw-voice-decode-");
        Path silkFile = workDir.resolve("input.silk");
        Path pcmFile = workDir.resolve("output.pcm");
        try {
            Files.write(silkFile, silkAudio);
            commandExecutor.execute(List.of(
                    properties.getAudio().getSilkDecoderPath(),
                    silkFile.toString(), pcmFile.toString(),
                    "-Fs_API", "16000", "-quiet"), properties.getAudio().getProcessTimeout());
            return readValidPcm(pcmFile);
        } finally {
            deleteQuietly(pcmFile);
            deleteQuietly(silkFile);
            deleteQuietly(workDir);
        }
    }

    @Override
    public byte[] pcmToSilk(byte[] pcmAudio) throws IOException {
        validatePcm(pcmAudio);
        if (properties.getAudio().getSilkEncoderPath().isBlank()) {
            throw new IOException("SILK 编码器未配置");
        }

        Path workDir = Files.createTempDirectory("claw-voice-encode-");
        Path pcmFile = workDir.resolve("input.pcm");
        Path silkFile = workDir.resolve("output.silk");
        try {
            Files.write(pcmFile, pcmAudio);
            commandExecutor.execute(List.of(
                    properties.getAudio().getSilkEncoderPath(),
                    pcmFile.toString(), silkFile.toString(),
                    "-Fs_API", "16000", "-rate", "24000", "-quiet", "-tencent"),
                    properties.getAudio().getProcessTimeout());
            byte[] silk = Files.readAllBytes(silkFile);
            validateTencentSilk(silk);
            return silk;
        } finally {
            deleteQuietly(silkFile);
            deleteQuietly(pcmFile);
            deleteQuietly(workDir);
        }
    }

    private byte[] readValidPcm(Path pcmFile) throws IOException {
        byte[] pcm = Files.readAllBytes(pcmFile);
        validatePcm(pcm);
        return pcm;
    }

    private void validatePcm(byte[] pcm) throws IOException {
        if (pcm == null || pcm.length == 0 || pcm.length > MAX_AUDIO_BYTES || pcm.length % 2 != 0) {
            throw new IOException("PCM 音频大小无效");
        }
    }

    private void validateSilk(byte[] audio) throws IOException {
        if (audio == null || audio.length < WECHAT_SILK_HEADER.length) {
            throw new IOException("语音数据为空或过短");
        }
        int offset = audio[0] == 0x02 ? 1 : 0;
        if (audio.length < offset + WECHAT_SILK_HEADER.length
                || !Arrays.equals(audio, offset, offset + WECHAT_SILK_HEADER.length,
                WECHAT_SILK_HEADER, 0, WECHAT_SILK_HEADER.length)) {
            throw new IOException("不支持的语音格式，预期为 SILK V3");
        }
    }

    private void validateTencentSilk(byte[] audio) throws IOException {
        validateSilk(audio);
        if (audio[0] != 0x02) {
            throw new IOException("SILK 编码结果缺少微信格式前导字节");
        }
    }

    private static void executeCommand(List<String> command, Duration timeout) throws IOException {
        Path output = Files.createTempFile("claw-voice-command-", ".log");
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile())
                    .start();
            boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new IOException("语音转换命令执行超时");
            }
            if (process.exitValue() != 0) {
                String detail = Files.readString(output);
                throw new IOException("语音转换命令执行失败，退出码 " + process.exitValue()
                        + (detail.isBlank() ? "" : "：" + detail.substring(0, Math.min(200, detail.length()))));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("语音转换命令被中断", e);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            deleteQuietly(output);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    @FunctionalInterface
    interface CommandExecutor {
        void execute(List<String> command, Duration timeout) throws IOException;
    }
}
