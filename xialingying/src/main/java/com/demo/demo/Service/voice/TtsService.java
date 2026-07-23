package com.demo.demo.Service.voice;

import java.io.IOException;

public interface TtsService {

    boolean isConfigured();

    byte[] synthesize(String text) throws IOException;
}
