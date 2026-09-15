package com.example.idempotency.store;

import com.example.idempotency.exception.IdempotencyException;
import com.example.idempotency.spi.ResultSerializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

/**
 * Java serialization fallback, so the core is usable without pulling in a
 * serialization library. Requires results to implement {@link java.io.Serializable}
 * and ties the stored bytes to your class layout — prefer the starter's Jackson
 * serializer whenever records outlive a deployment.
 */
public final class JdkResultSerializer implements ResultSerializer {

    @Override
    public byte[] serialize(Object value) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        } catch (IOException e) {
            throw new IdempotencyException("Could not serialize result of type " + value.getClass().getName(), e);
        }
        return bytes.toByteArray();
    }

    @Override
    public Object deserialize(byte[] payload, String resultType) {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(payload))) {
            return in.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new IdempotencyException("Could not deserialize stored result of type " + resultType, e);
        }
    }
}
