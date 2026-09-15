package com.example.idempotency.spi;

/**
 * Turns a method result into the bytes a store keeps, and back again on replay.
 * Kept as an SPI so the core needs no serialization library; the Spring starter
 * binds a Jackson implementation, and {@code JdkResultSerializer} covers plain
 * Java usage.
 */
public interface ResultSerializer {

    /** Serializes a non-null result. */
    byte[] serialize(Object value);

    /**
     * Deserializes a previously stored result.
     *
     * @param payload    bytes produced by {@link #serialize(Object)}
     * @param resultType class name recorded alongside the payload
     */
    Object deserialize(byte[] payload, String resultType);
}
