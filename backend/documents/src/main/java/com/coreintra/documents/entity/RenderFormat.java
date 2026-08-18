package com.coreintra.documents.entity;

/**
 * An export target: what a render produced.
 *
 * <p>Wider than {@link DocumentFormat} because a render is one-way. PDF is the
 * archived, reproducible artefact (brief 6.4); the rest exist because clients ask
 * for them.
 */
public enum RenderFormat {

    /** The archived form. Byte-for-byte reproducible only for MDV; pinned otherwise. */
    PDF,

    DOCX,

    /** Legacy binary Word. Goes through LibreOffice and is labelled lossy in the UI. */
    DOC,

    HWP,

    HWPX,

    HTML,

    MDV
}
