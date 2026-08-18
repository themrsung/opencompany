package com.coreintra.documents.internal;

/**
 * One format's door onto {@link InternalDoc}.
 *
 * <p>Adding a format costs one implementation of this, not a converter to every
 * other format. That is the whole reason the pivot model exists.
 *
 * <p>Implementations must never silently drop content. Anything the model does
 * not represent becomes an {@link InternalDoc.OpaqueBlock}, and anything that
 * genuinely cannot survive a write is declared in {@link #capabilities()} so the
 * user is warned with specifics before saving.
 */
public interface DocumentAdapter {

    /** What this format can carry. Drives the fidelity matrix and export warnings. */
    FormatCapabilities capabilities();

    /**
     * Parses a document into the pivot model.
     *
     * @throws com.coreintra.documents.ooxml.OoxmlException if the bytes are not
     *         this format, with a message naming the likely actual type
     */
    InternalDoc read(byte[] content);

    /**
     * Writes the pivot model out.
     *
     * @throws UnsupportedOperationException when {@link FormatCapabilities#canWrite()}
     *         is false — a read-only format must refuse rather than produce
     *         something approximate that looks authoritative
     */
    byte[] write(InternalDoc document);

    /** Cheap sniff, so an upload is routed to the right adapter before parsing. */
    boolean looksLikeThisFormat(byte[] content, String filename);
}
