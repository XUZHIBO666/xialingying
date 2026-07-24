package com.demo.demo.Service.voice;

import java.io.IOException;

public interface AsrService {

    boolean isConfigured();

    /**
     * 将 16 kHz、单声道、16-bit PCM S16LE 音频识别为纯文本。
     *
     * @param pcmAudio 裸 PCM 文件字节
     * @return 非空识别文本
     */
    String transcribe(byte[] pcmAudio) throws IOException;
}
