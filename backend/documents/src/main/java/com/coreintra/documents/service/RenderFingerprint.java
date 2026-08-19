package com.coreintra.documents.service;

import java.nio.charset.StandardCharsets;

import com.coreintra.documents.blob.LocalFileBlobStore;
import com.coreintra.documents.entity.RenderFormat;
import com.coreintra.documents.render.RenderMetadata;

/**
 * The identity of a render's configuration, as one hash.
 *
 * <p>Everything that could make the same document produce different bytes goes in: the
 * renderer version, the template version, the resolved font set, every substitution, and
 * the locale. Two renders with the same fingerprint should be byte-identical, and the
 * archived one is therefore a legitimate answer to a request for a new one.
 *
 * <p>Two renders with <em>different</em> fingerprints are allowed to differ, and the
 * stored metadata says why. That is the whole reproducibility contract in brief 6.4: we
 * cannot make LibreOffice deterministic, so we record what we cannot control.
 */
public final class RenderFingerprint {

    private RenderFingerprint() {
    }

    public static String of(RenderFormat format, RenderMetadata metadata) {
        return LocalFileBlobStore.sha256Hex(canonical(format, metadata).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The text the fingerprint is taken over. Exposed because a fingerprint mismatch is
     * unreadable and this is not: an operator asking "why did it re-render?" gets an answer.
     */
    public static String canonical(RenderFormat format, RenderMetadata metadata) {
        StringBuilder out = new StringBuilder();
        out.append("format=").append(format.name()).append('\n');
        out.append("renderer=").append(nullToEmpty(metadata.rendererVersion())).append('\n');
        out.append("template=").append(nullToEmpty(metadata.templateId()))
           .append('@').append(metadata.templateVersion()).append('\n');
        out.append("locale=").append(nullToEmpty(metadata.locale())).append('\n');
        out.append("fonts\n").append(RenderMetadataCodec.encodeFontSet(metadata.fontSet())).append('\n');
        out.append("substitutions\n")
           .append(RenderMetadataCodec.encodeSubstitutions(metadata.substitutions())).append('\n');
        out.append("extra\n").append(RenderMetadataCodec.encodeExtra(metadata.extra()));
        return out.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
