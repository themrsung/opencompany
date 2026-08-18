package com.coreintra.documents.service;

import com.coreintra.documents.font.FontRecord;

/**
 * What a font says about embedding, as read and as written.
 *
 * <p>Two values that are always set together and are never useful apart: our reading of the
 * OS/2 {@code fsType} bits, and the bits themselves. The reading drives the manager UI's
 * wording; the raw value is shown beside it so a client can see the font's own declaration
 * rather than only our summary of it.
 *
 * <p>We report and never enforce. Honouring an embedding restriction is the client's
 * obligation (brief 6.9), and a check we do not perform must not be implied by a field
 * name that sounds like one.
 */
public final class EmbeddingDeclaration {

    private final FontRecord.EmbeddingPermission permission;

    private final Integer fsTypeRaw;

    public EmbeddingDeclaration(FontRecord.EmbeddingPermission permission, Integer fsTypeRaw) {
        this.permission = permission == null ? FontRecord.EmbeddingPermission.UNKNOWN : permission;
        this.fsTypeRaw = fsTypeRaw;
    }

    /** Used when the OS/2 table could not be read at all. Honest, rather than assumed permissive. */
    public static EmbeddingDeclaration unreadable() {
        return new EmbeddingDeclaration(FontRecord.EmbeddingPermission.UNKNOWN, null);
    }

    public FontRecord.EmbeddingPermission permission() {
        return permission;
    }

    /** The raw fsType bits, or null when unreadable. */
    public Integer fsTypeRaw() {
        return fsTypeRaw;
    }
}
