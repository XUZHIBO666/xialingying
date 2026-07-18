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
public class AudioConverter {

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

    public boolean isConfigured() {
        return !properties.getAudio().getSilkDecoderPath().isBlank()
                && !properties.getAudio().getFfmpegPath().isBlank();
    }

    /**
     * 将微信下载得到的 SILK V3 字节转换为 16 kHz、单声道、16-bit PCM WAV。
     * 输入允许以微信前导字节 {@code 0x02} + {@code #!SILK_V3} 开头。
     *
     * @param silkAudio 微信语音原始字节
     * @return 完整 WAV 文件字节
     */
    public byte[] convertToWav(byte[] silkAudio) throws IOException {
        validateSilk(silkAudio);
        if (!isConfigured()) {
            throw new IOException("语音转换工具未配置");
        }

        Path workDir = Files.createTempDirectory("claw-voice-");
        Path silkFile = workDir.resolve("input.silk");
        Path pcmFile = workDir.resolve("decoded.pcm");
        Path wavFile = workDir.resolve("output.wav");
        try {
            Files.write(silkFile, silkAudio);
            Duration timeout = properties.getAudio().getProcessTimeout();
            commandExecutor.execute(List.of(
                    properties.getAudio().getSilkDecoderPath(),
                    silkFile.toString(), pcmFile.toString(),
                    "-Fs_API", "16000", "-quiet"), timeout);
            commandExecutor.execute(List.of(
                    properties.getAudio().getFfmpegPath(),
                    "-y", "-f", "s16le", "-ar", "16000", "-ac", "1",
                    "-i", pcmFile.toString(),
                    "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le",
                    wavFile.toString()), timeout);

            byte[] wav = Files.readAllBytes(wavFile);
            if (wav.length == 0 || wav.length > MAX_AUDIO_BYTES) {
                throw new IOException("转换后的语音文件大小无效");
            }
            return wav;
        } finally {
            deleteQuietly(wavFile);
            deleteQuietly(pcmFile);
            deleteQuietly(silkFile);
            deleteQuietly(workDir);
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
