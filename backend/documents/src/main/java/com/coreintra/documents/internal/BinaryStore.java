package com.coreintra.documents.internal;

/**
 * Where an adapter gets image bytes from, and puts them back.
 *
 * <h2>Why the pivot model does not carry the bytes</h2>
 *
 * <p>{@link InternalDoc.Image} holds a SHA-256, not a {@code byte[]}. A board
 * pack with forty charts would otherwise sit in memory twice — once in the
 * pivot model and once in the package being written — and the pivot model is
 * held across an editing session, not just across a conversion.
 *
 * <p>So the bytes live in the blob store and the model refers to them. This is
 * the seam between the two, small enough that a test can implement it with a
 * map and production can implement it with {@code BlobService}.
 *
 * <p>An adapter given no store must <b>refuse</b> a document containing images
 * rather than writing the document without them. Silently dropping a 도장 or a
 * signature scan from an export is exactly the failure the fidelity rules exist
 * to prevent.
 */
public interface BinaryStore {

    /**
     * @return the stored content
     * @throws RuntimeException if nothing is stored under that hash — a missing
     *         image is a broken document, not an empty one
     */
    byte[] load(String sha256);

    /**
     * Stores content, returning its SHA-256.
     *
     * <p>Content-addressed and therefore idempotent: the same image embedded in
     * twenty documents is stored once.
     */
    String store(byte[] content, String mediaType);
}
