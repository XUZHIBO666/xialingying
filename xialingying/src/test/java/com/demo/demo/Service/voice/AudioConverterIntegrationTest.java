package com.demo.demo.Service.voice;

import com.demo.demo.config.VoiceProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AudioConverterIntegrationTest {

    @Test
    void realEncoderProducesWechatSilkThatDecoderCanRead() throws Exception {
        String encoder = System.getenv("VOICE_SILK_ENCODER_PATH");
        String decoder = System.getenv("VOICE_SILK_DECODER_PATH");
        assumeTrue(encoder != null && Files.isRegularFile(Path.of(encoder)));
        assumeTrue(decoder != null && Files.isRegularFile(Path.of(decoder)));

        VoiceProperties properties = new VoiceProperties();
        properties.getAudio().setSilkEncoderPath(encoder);
        properties.getAudio().setSilkDecoderPath(decoder);
        AudioConverter converter = new AudioConverter(properties);
        byte[] pcm = new byte[3200];

        byte[] silk = converter.pcmToSilk(pcm);
        byte[] decoded = converter.silkToPcm(silk);

        assertTrue(silk.length > 10);
        int headerOffset = silk[0] == 0x02 ? 1 : 0;
        assertArrayEquals("#!SILK_V3".getBytes(StandardCharsets.US_ASCII),
                java.util.Arrays.copyOfRange(silk, headerOffset, headerOffset + 9));
        assertTrue(decoded.length > 0);
        assertEqualsEven(decoded.length);
    }

    private void assertEqualsEven(int value) {
        assertTrue(value % 2 == 0);
    }
}
