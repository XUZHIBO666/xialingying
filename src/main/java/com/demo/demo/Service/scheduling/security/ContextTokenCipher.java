package com.demo.demo.Service.scheduling.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encrypt/decrypt for contextToken.
 *
 * <p>Ciphertext format: Base64(12-byte IV || ciphertext || 16-byte GCM tag).
 * The key is read from {@code scheduling.cipher.key}; if absent, a
 * one-time key is generated — old tokens become undecryptable on restart.
 */
@Slf4j
@Component
public class ContextTokenCipher {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128; // bits
    private static final int AES_KEY_SIZE = 256;   // bits

    private final SecretKey key;

    public ContextTokenCipher(
            @Value("${scheduling.cipher.key:}") String base64Key) {
        if (base64Key != null && !base64Key.isBlank()) {
            byte[] decoded = Base64.getDecoder().decode(base64Key);
            this.key = new SecretKeySpec(decoded, ALGORITHM);
            log.info("[Cipher] Using configured AES-256 key");
        } else {
            this.key = generateKey();
            log.warn("[Cipher] No scheduling.cipher.key set — using one-time key. "
                    + "Encrypted tokens will be lost on restart. "
                    + "Set SCHEDULING_CIPHER_KEY env var with a Base64-encoded 256-bit key.");
        }
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] encrypted = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] combined = new byte[GCM_IV_LENGTH + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, GCM_IV_LENGTH);
            System.arraycopy(encrypted, 0, combined, GCM_IV_LENGTH, encrypted.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new CipherException("Encryption failed", e);
        }
    }

    public String decrypt(String base64Ciphertext) {
        try {
            byte[] combined = Base64.getDecoder().decode(base64Ciphertext);

            if (combined.length < GCM_IV_LENGTH + GCM_TAG_LENGTH / 8) {
                throw new CipherException("Ciphertext too short");
            }

            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);

            byte[] encrypted = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, GCM_IV_LENGTH, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, java.nio.charset.StandardCharsets.UTF_8);
        } catch (CipherException e) {
            throw e;
        } catch (Exception e) {
            throw new CipherException("Decryption failed", e);
        }
    }

    private static SecretKey generateKey() {
        try {
            KeyGenerator kg = KeyGenerator.getInstance(ALGORITHM);
            kg.init(AES_KEY_SIZE);
            return kg.generateKey();
        } catch (Exception e) {
            throw new CipherException("Failed to generate AES key", e);
        }
    }
}
