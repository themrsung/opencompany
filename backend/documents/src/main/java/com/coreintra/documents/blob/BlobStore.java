package com.coreintra.documents.blob;

import java.io.InputStream;

/**
 * Content-addressed storage for document bytes and uploaded fonts.
 *
 * <p>An interface with a local-volume implementation, deliberately. The
 * deployment target is one box that may have no outbound internet, so no cloud
 * object store is assumed — but an S3 implementation can be dropped in behind
 * this without any caller changing.
 *
 * <h2>Content addressing</h2>
 *
 * <p>The key is the SHA-256 of the content. Three consequences that matter here:
 * storing the same bytes twice costs one copy; a stored document cannot be
 * modified in place, only superseded, which is what "the submitted artefact is
 * immutable" requires; and the approval trail's hash <em>is</em> the storage
 * key, so proving what was approved is a lookup rather than a comparison.
 */
public interface BlobStore {

    /**
     * Stores content and returns its hash.
     *
     * <p>Idempotent: storing identical bytes twice yields the same key and does
     * not duplicate storage.
     */
    BlobRef put(byte[] content, String contentType, String originalFilename);

    /** @throws BlobNotFoundException if no blob has that hash */
    byte[] get(String sha256);

    /** Streamed read, for documents too large to hold in memory. */
    InputStream openStream(String sha256);

    boolean exists(String sha256);

    /** Metadata without reading the content. */
    BlobRef describe(String sha256);

    /**
     * Removes a blob.
     *
     * <p>Rarely correct: an approved document's bytes must remain retrievable
     * forever, and original uploads are never overwritten. Reserved for
     * genuinely orphaned content, and audited when it happens.
     */
    void delete(String sha256);

    /** Thrown when a hash names nothing. */
    class BlobNotFoundException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public BlobNotFoundException(String sha256) {
            super("No stored content with hash " + sha256);
        }
    }
}
