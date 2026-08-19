package com.coreintra.documents.entity;

/**
 * A format a document <em>body</em> can be stored in.
 *
 * <p>Narrower than the render targets in {@link RenderFormat}: these are the four
 * formats this system can parse back into {@code InternalDoc} and edit. {@code DOC}
 * and {@code PDF} and {@code HTML} are exports only and are absent here deliberately.
 */
public enum DocumentFormat {

    /** OOXML. The canonical storage, edit and template format (brief 6.1). */
    DOCX,

    /** OWPML, the KS X 6101 open XML standard. The primary HWP-family write path. */
    HWPX,

    /** HWP 5.0 binary. Read-only in practice: hwplib writes it, hwpxlib is preferred. */
    HWP,

    /** Markdown Visual. Rendered by the Node conversion worker, never by the JVM. */
    MDV
}
