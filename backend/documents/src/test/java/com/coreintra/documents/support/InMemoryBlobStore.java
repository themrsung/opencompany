package com.coreintra.documents.support;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import com.coreintra.documents.blob.BlobRef;
import com.coreintra.documents.blob.BlobStore;
import com.coreintra.documents.blob.LocalFileBlobStore;

/**
 * A content-addressed store in a map.
 *
 * <p>Uses the production hash function rather than a stand-in, so a test that asserts on a
 * hash is asserting on the same value the real store would produce.
 */
public final class InMemoryBlobStore implements BlobStore {

    private final Map<String, byte[]> content = new LinkedHashMap<String, byte[]>();

    private final Map<String, BlobRef> refs = new LinkedHashMap<String, BlobRef>();

    @Override
    public BlobRef put(byte[] bytes, String contentType, String originalFilename) {
        String hash = LocalFileBlobStore.sha256Hex(bytes);
        if (!content.containsKey(hash)) {
            content.put(hash, bytes.clone());
            refs.put(hash, new BlobRef(hash, bytes.length, contentType, originalFilename,
                    OffsetDateTime.now()));
        }
        return refs.get(hash);
    }

    @Override
    public byte[] get(String sha256) {
        byte[] bytes = content.get(sha256);
        if (bytes == null) {
            throw new BlobNotFoundException(sha256);
        }
        return bytes.clone();
    }

    @Override
    public InputStream openStream(String sha256) {
        return new ByteArrayInputStream(get(sha256));
    }

    @Override
    public boolean exists(String sha256) {
        return content.containsKey(sha256);
    }

    @Override
    public BlobRef describe(String sha256) {
        BlobRef ref = refs.get(sha256);
        if (ref == null) {
            throw new BlobNotFoundException(sha256);
        }
        return ref;
    }

    @Override
    public void delete(String sha256) {
        content.remove(sha256);
        refs.remove(sha256);
    }
}
