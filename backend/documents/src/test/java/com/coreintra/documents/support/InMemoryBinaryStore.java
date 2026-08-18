package com.coreintra.documents.support;

import com.coreintra.documents.internal.BinaryStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/** A {@link BinaryStore} backed by a map, hashing exactly as the real one does. */
public final class InMemoryBinaryStore implements BinaryStore {

    private final Map<String, byte[]> content = new LinkedHashMap<String, byte[]>();

    @Override
    public byte[] load(String sha256) {
        byte[] found = content.get(sha256);
        if (found == null) {
            throw new IllegalStateException("no stored content with hash " + sha256);
        }
        return found.clone();
    }

    @Override
    public String store(byte[] bytes, String mediaType) {
        String sha256 = sha256(bytes);
        content.put(sha256, bytes.clone());
        return sha256;
    }

    public int size() {
        return content.size();
    }

    public static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder hex = new StringBuilder(64);
            for (int index = 0; index < hash.length; index++) {
                hex.append(Character.forDigit((hash[index] >> 4) & 0xF, 16));
                hex.append(Character.forDigit(hash[index] & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }
}
