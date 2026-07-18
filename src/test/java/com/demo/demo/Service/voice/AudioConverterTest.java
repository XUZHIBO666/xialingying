package com.demo.demo.Service.voice;

import com.demo.demo.config.VoiceProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AudioConverterTest {

    @Test
    void convertsWechatSilkTo16kMonoWav() throws Exception {
        VoiceProperties properties = properties();
        List<List<String>> commands = new ArrayList<>();
        byte[] expectedWav = "RIFF-test-WAVE".getBytes(StandardCharsets.US_ASCII);
        AudioConverter converter = new AudioConverter(properties, (command, timeout) -> {
            commands.add(command);
            if (command.get(0).equals("decoder.exe")) {
                Files.write(Path.of(command.get(2)), new byte[]{1, 2, 3, 4});
            } else {
                Files.write(Path.of(command.get(command.size() - 1)), expectedWav);
            }
        });

        byte[] silk = concat(new byte[]{0x02}, "#!SILK_V3payload".getBytes(StandardCharsets.US_ASCII));
        byte[] actual = converter.convertToWav(silk);

        assertArrayEquals(expectedWav, actual);
        assertEquals("decoder.exe", commands.get(0).get(0));
        assertEquals("-Fs_API", commands.get(0).get(3));
        assertEquals("16000", commands.get(0).get(4));
        assertEquals("ffmpeg.exe", commands.get(1).get(0));
        assertEquals("16000", commands.get(1).get(5));
        assertEquals("1", commands.get(1).get(7));
    }

    @Test
    void rejectsUnknownAudioBeforeStartingCommands() {
        VoiceProperties properties = properties();
        AudioConverter converter = new AudioConverter(properties, (command, timeout) -> {
            throw new AssertionError("command must not run");
        });

        assertThrows(IOException.class, () -> converter.convertToWav(new byte[]{1, 2, 3}));
    }

    private VoiceProperties properties() {
        VoiceProperties properties = new VoiceProperties();
        properties.getAudio().setSilkDecoderPath("decoder.exe");
        properties.getAudio().setFfmpegPath("ffmpeg.exe");
        return properties;
    }

    private byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
