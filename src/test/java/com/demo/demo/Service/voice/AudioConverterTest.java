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
    void convertsWechatSilkTo16kMonoPcm() throws Exception {
        VoiceProperties properties = properties();
        List<List<String>> commands = new ArrayList<>();
        byte[] expectedPcm = new byte[]{1, 0, 2, 0};
        AudioConverter converter = new AudioConverter(properties, (command, timeout) -> {
            commands.add(command);
            Files.write(Path.of(command.get(2)), expectedPcm);
        });

        byte[] silk = concat(new byte[]{0x02}, "#!SILK_V3payload".getBytes(StandardCharsets.US_ASCII));
        byte[] actual = converter.silkToPcm(silk);

        assertArrayEquals(expectedPcm, actual);
        assertEquals(1, commands.size());
        assertEquals("decoder.exe", commands.get(0).get(0));
        assertEquals("-Fs_API", commands.get(0).get(3));
        assertEquals("16000", commands.get(0).get(4));
    }

    @Test
    void converts16kMonoPcmToWechatSilk() throws Exception {
        VoiceProperties properties = properties();
        List<String> command = new ArrayList<>();
        byte[] expectedSilk = concat(new byte[]{0x02},
                "#!SILK_V3payload".getBytes(StandardCharsets.US_ASCII));
        AudioConverter converter = new AudioConverter(properties, (actualCommand, timeout) -> {
            command.addAll(actualCommand);
            Files.write(Path.of(actualCommand.get(2)), expectedSilk);
        });

        byte[] actual = converter.pcmToSilk(new byte[]{1, 0, 2, 0});

        assertArrayEquals(expectedSilk, actual);
        assertEquals("encoder.exe", command.get(0));
        assertEquals("-Fs_API", command.get(3));
        assertEquals("16000", command.get(4));
        assertEquals("-tencent", command.get(command.size() - 1));
    }

    @Test
    void rejectsEncoderOutputWithoutTencentPrefix() {
        VoiceProperties properties = properties();
        AudioConverter converter = new AudioConverter(properties, (command, timeout) ->
                Files.write(Path.of(command.get(2)),
                        "#!SILK_V3payload".getBytes(StandardCharsets.US_ASCII)));

        assertThrows(IOException.class, () -> converter.pcmToSilk(new byte[]{1, 0, 2, 0}));
    }

    @Test
    void rejectsUnknownAudioBeforeStartingCommands() {
        VoiceProperties properties = properties();
        AudioConverter converter = new AudioConverter(properties, (command, timeout) -> {
            throw new AssertionError("command must not run");
        });

        assertThrows(IOException.class, () -> converter.silkToPcm(new byte[]{1, 2, 3}));
    }

    private VoiceProperties properties() {
        VoiceProperties properties = new VoiceProperties();
        properties.getAudio().setSilkDecoderPath("decoder.exe");
        properties.getAudio().setSilkEncoderPath("encoder.exe");
        return properties;
    }

    private byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
