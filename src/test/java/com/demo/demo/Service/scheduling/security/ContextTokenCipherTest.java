package com.demo.demo.Service.scheduling.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContextTokenCipherTest {

    private final ContextTokenCipher cipher = new ContextTokenCipher(""); // auto-generate key

    @Test
    void encryptThenDecryptShouldReturnOriginal() {
        String plaintext = "test-context-token-abc-123";
        String encrypted = cipher.encrypt(plaintext);
        String decrypted = cipher.decrypt(encrypted);

        assertEquals(plaintext, decrypted);
    }

    @Test
    void encryptionShouldProduceDifferentOutputEachTime() {
        String plaintext = "same-token";
        String enc1 = cipher.encrypt(plaintext);
        String enc2 = cipher.encrypt(plaintext);

        assertNotEquals(enc1, enc2, "GCM random IV should produce different ciphertexts");
        // Both should decrypt to the same value
        assertEquals(plaintext, cipher.decrypt(enc1));
        assertEquals(plaintext, cipher.decrypt(enc2));
    }

    @Test
    void encryptedOutputShouldBeBase64() {
        String encrypted = cipher.encrypt("hello");
        // Should be valid Base64 and decode to more than 12+16 bytes (IV+GCM tag overhead)
        byte[] decoded = java.util.Base64.getDecoder().decode(encrypted);
        assertTrue(decoded.length > 28, "Should have IV(12) + encrypted + tag(16)");
    }

    @Test
    void decryptionShouldFailOnCorruptedData() {
        String encrypted = cipher.encrypt("hello");
        assertThrows(CipherException.class, () ->
                cipher.decrypt(encrypted.substring(0, encrypted.length() - 4)));
    }

    @Test
    void decryptionShouldFailOnRandomBytes() {
        assertThrows(CipherException.class, () ->
                cipher.decrypt("not-valid-base64!!!"));
    }

    @Test
    void shouldHandleEmptyString() {
        String encrypted = cipher.encrypt("");
        assertEquals("", cipher.decrypt(encrypted));
    }

    @Test
    void shouldHandleUnicodeContent() {
        String plaintext = "杭州天气推送 🌤️";
        String encrypted = cipher.encrypt(plaintext);
        assertEquals(plaintext, cipher.decrypt(encrypted));
    }

    @Test
    void differentKeysShouldProduceDifferentCiphertexts() {
        ContextTokenCipher cipher2 = new ContextTokenCipher("");
        String plaintext = "same-data";
        String enc1 = cipher.encrypt(plaintext);
        String enc2 = cipher2.encrypt(plaintext);

        // Can't decrypt with wrong key
        assertThrows(CipherException.class, () -> cipher2.decrypt(enc1));
        assertEquals(plaintext, cipher2.decrypt(enc2));
    }

    @Test
    void configuredKeyShouldWork() throws Exception {
        // Generate a valid Base64 256-bit key
        javax.crypto.KeyGenerator kg = javax.crypto.KeyGenerator.getInstance("AES");
        kg.init(256);
        String base64Key = java.util.Base64.getEncoder().encodeToString(
                kg.generateKey().getEncoded());

        ContextTokenCipher configured = new ContextTokenCipher(base64Key);
        String encrypted = configured.encrypt("test");
        assertEquals("test", configured.decrypt(encrypted));
    }
}
