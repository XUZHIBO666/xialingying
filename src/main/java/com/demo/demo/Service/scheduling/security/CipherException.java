package com.demo.demo.Service.scheduling.security;

/**
 * Unchecked exception for encryption/decryption failures.
 */
public class CipherException extends RuntimeException {
    public CipherException(String message) {
        super(message);
    }

    public CipherException(String message, Throwable cause) {
        super(message, cause);
    }
}
