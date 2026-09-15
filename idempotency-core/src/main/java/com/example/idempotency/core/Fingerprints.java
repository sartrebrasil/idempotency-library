package com.example.idempotency.core;

import com.example.idempotency.exception.IdempotencyException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 hex digests, used to detect a key reused with a different payload. */
public final class Fingerprints {

    private Fingerprints() {
    }

    public static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            // Every JRE ships SHA-256; if it is missing the platform is broken.
            throw new IdempotencyException("SHA-256 is not available", e);
        }
    }

    public static String sha256Hex(String data) {
        return sha256Hex(data.getBytes(StandardCharsets.UTF_8));
    }
}
