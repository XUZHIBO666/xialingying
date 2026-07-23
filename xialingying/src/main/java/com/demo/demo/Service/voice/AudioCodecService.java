package com.demo.demo.Service.voice;

import java.io.IOException;

public interface AudioCodecService {

    byte[] silkToPcm(byte[] silkAudio) throws IOException;

    byte[] pcmToSilk(byte[] pcmAudio) throws IOException;
}
