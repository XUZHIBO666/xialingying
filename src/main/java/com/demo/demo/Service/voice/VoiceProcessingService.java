package com.demo.demo.Service.voice;

import com.lth.wechat.ilink.dto.message.VoiceContent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
@Service
public class VoiceProcessingService {

    private final AudioConverter audioConverter;
    private final AsrService asrService;

    public VoiceProcessingService(AudioConverter audioConverter, AsrService asrService) {
        this.audioConverter = audioConverter;
        this.asrService = asrService;
    }

    public Result recognize(VoiceContent voice, Supplier<byte[]> downloader) throws IOException {
        if (voice == null) {
            throw new IOException("语音消息内容为空");
        }
        String officialText = voice.getText() == null ? "" : voice.getText().trim();
        if (!audioConverter.isConfigured() || !asrService.isConfigured()) {
            if (!officialText.isEmpty()) {
                return new Result(officialText, "official", 0, 0, 0);
            }
            throw new IOException("ASR 或语音转换工具未配置");
        }

        long downloadStart = System.nanoTime();
        try {
            byte[] source = downloader.get();
            long downloadMs = elapsed(downloadStart);
            long convertStart = System.nanoTime();
            byte[] wav = audioConverter.convertToWav(source);
            long convertMs = elapsed(convertStart);
            long asrStart = System.nanoTime();
            String text = asrService.transcribe(wav);
            return new Result(text, "asr", downloadMs, convertMs, elapsed(asrStart));
        } catch (Exception e) {
            if (!officialText.isEmpty()) {
                log.warn("[语音处理] ASR 失败，使用官方文本兜底: {}", e.getMessage());
                return new Result(officialText, "official-fallback", elapsed(downloadStart), 0, 0);
            }
            if (e instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("语音处理失败", e);
        }
    }

    private long elapsed(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    public record Result(String text, String source, long downloadMs, long convertMs, long asrMs) {
    }
}
