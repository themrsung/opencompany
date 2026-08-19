package com.coreintra.documents.blob;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Blob storage on a local volume.
 *
 * <p>Files are sharded two levels deep by the first four hex characters
 * ({@code ab/cd/abcd...}). A single flat directory with a hundred thousand
 * documents in it is slow to list and, on some filesystems, slow to open.
 *
 * <p>Writes go to a temporary file and are then moved atomically into place, so
 * a crash mid-write cannot leave a truncated file sitting under a hash that
 * claims to describe complete content — which would be undetectable corruption
 * of an approved document.
 */
public class LocalFileBlobStore implements BlobStore {

    private final Path root;

    public LocalFileBlobStore(String rootPath) {
        this.root = Paths.get(rootPath);
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "cannot create the blob directory at " + rootPath
                            + ". Check the volume is mounted and writable.", e);
        }
    }

    @Override
    public BlobRef put(byte[] content, String contentType, String originalFilename) {
        if (content == null) {
            throw new NullPointerException("content");
        }
        String hash = sha256Hex(content);
        Path target = pathFor(hash);
        try {
            if (Files.exists(target)) {
                // Identical bytes are already stored. Content addressing makes
                // this free rather than a duplicate.
                return describe(hash);
            }
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), "blob-", ".tmp");
            Files.write(temporary, content);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            return new BlobRef(hash, content.length, contentType, originalFilename,
                    OffsetDateTime.now(ZoneOffset.UTC));
        } catch (IOException e) {
            throw new IllegalStateException("failed to store content " + hash, e);
        }
    }

    @Override
    public byte[] get(String sha256) {
        Path path = pathFor(sha256);
        if (!Files.exists(path)) {
            throw new BlobNotFoundException(sha256);
        }
        try {
            byte[] content = Files.readAllBytes(path);
            // Verify on read. Silent bit-rot in an archived approval is exactly
            // the failure this system must never have, and the check is cheap
            // next to the disk read.
            String actual = sha256Hex(content);
            if (!actual.equals(sha256)) {
                throw new IllegalStateException(
                        "stored content at " + sha256 + " hashes to " + actual
                                + ". The file has been corrupted or replaced; restore from backup "
                                + "rather than using it.");
            }
            return content;
        } catch (IOException e) {
            throw new IllegalStateException("failed to read content " + sha256, e);
        }
    }

    @Override
    public InputStream openStream(String sha256) {
        return new ByteArrayInputStream(get(sha256));
    }

    @Override
    public boolean exists(String sha256) {
        return Files.exists(pathFor(sha256));
    }

    @Override
    public BlobRef describe(String sha256) {
        Path path = pathFor(sha256);
        if (!Files.exists(path)) {
            throw new BlobNotFoundException(sha256);
        }
        try {
            return new BlobRef(sha256, Files.size(path), null, null,
                    OffsetDateTime.ofInstant(Files.getLastModifiedTime(path).toInstant(),
                            ZoneOffset.UTC));
        } catch (IOException e) {
            throw new IllegalStateException("failed to describe content " + sha256, e);
        }
    }

    @Override
    public void delete(String sha256) {
        try {
            Files.deleteIfExists(pathFor(sha256));
        } catch (IOException e) {
            throw new IllegalStateException("failed to delete content " + sha256, e);
        }
    }

    private Path pathFor(String sha256) {
        String hash = requireHash(sha256);
        return root.resolve(hash.substring(0, 2))
                .resolve(hash.substring(2, 4))
                .resolve(hash);
    }

    /**
     * Rejects anything that is not a bare hex hash.
     *
     * <p>The hash becomes a path segment, so accepting a value containing
     * {@code ../} would be a path traversal reachable by anyone who can name a
     * blob.
     */
    private static String requireHash(String sha256) {
        if (sha256 == null || sha256.length() != 64) {
            throw new IllegalArgumentException("not a SHA-256 hash: " + sha256);
        }
        for (int i = 0; i < sha256.length(); i++) {
            char c = sha256.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                throw new IllegalArgumentException(
                        "not a lowercase hex SHA-256 hash: " + sha256);
            }
        }
        return sha256;
    }

    /** Lowercase hex SHA-256. The storage key and the approval-trail hash. */
    public static String sha256Hex(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable in this JRE", e);
        }
    }
}
