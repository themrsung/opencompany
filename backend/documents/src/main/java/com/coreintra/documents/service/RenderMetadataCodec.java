package com.coreintra.documents.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.coreintra.compat.Texts;
import com.coreintra.documents.entity.DocumentRenderEntity;
import com.coreintra.documents.render.RenderMetadata;

/**
 * Stores the render metadata as text a person can read during an incident.
 *
 * <p>Sorted, one entry per line. Sorted because the same font set assembled in a different
 * order must produce the same fingerprint - otherwise a re-render would "differ" for a
 * reason that is not a difference, and the archived-is-authoritative rule would fire on
 * noise. The substitution list is the one thing kept in its original order, because the
 * order a resolver tried its fallbacks in is part of what happened.
 */
public final class RenderMetadataCodec {

    private RenderMetadataCodec() {
    }

    public static String encodeFontSet(Map<String, String> fontSet) {
        List<String> lines = new ArrayList<String>();
        for (Map.Entry<String, String> entry : fontSet.entrySet()) {
            lines.add(entry.getKey() + "=" + entry.getValue());
        }
        Collections.sort(lines);
        return join(lines);
    }

    public static Map<String, String> decodeFontSet(String encoded) {
        return decodeMap(encoded);
    }

    /** Order preserved: which fallback was tried first is part of the record. */
    public static String encodeSubstitutions(List<String> substitutions) {
        return join(substitutions);
    }

    public static List<String> decodeSubstitutions(String encoded) {
        List<String> lines = new ArrayList<String>();
        if (Texts.isBlank(encoded)) {
            return lines;
        }
        String[] parts = encoded.split("\n", -1);
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                lines.add(parts[i]);
            }
        }
        return lines;
    }

    /** mdv's spec version, theme, buildTime, locale and timezone live here. */
    public static String encodeExtra(Map<String, String> extra) {
        return encodeFontSet(extra);
    }

    public static Map<String, String> decodeExtra(String encoded) {
        return decodeMap(encoded);
    }

    /**
     * Rebuilds the metadata of an archived render.
     *
     * <p>This is what makes the comparison in brief 6.4 possible at all: a regenerated PDF
     * that differs from the archived one can be explained field by field rather than
     * declared mysterious.
     */
    public static RenderMetadata toMetadata(DocumentRenderEntity render) {
        RenderMetadata.Builder builder = RenderMetadata.builder()
                .rendererVersion(render.rendererVersion())
                .documentSha256(render.documentSha256())
                .outputSha256(render.outputSha256())
                .renderedAt(render.renderedAt())
                .locale(render.locale());
        if (render.templateId() != null) {
            builder.template(render.templateId(),
                    render.templateVersionNo() == null ? 0 : render.templateVersionNo().intValue());
        }
        for (Map.Entry<String, String> entry : decodeFontSet(render.fontSet()).entrySet()) {
            builder.font(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, String> entry : decodeExtra(render.extra()).entrySet()) {
            builder.extra(entry.getKey(), entry.getValue());
        }
        return builder.build();
    }

    /** The renderedAt of a metadata record, or now when the renderer did not stamp one. */
    static OffsetDateTime renderedAtOrNow(RenderMetadata metadata) {
        return metadata.renderedAt() == null ? OffsetDateTime.now() : metadata.renderedAt();
    }

    private static Map<String, String> decodeMap(String encoded) {
        Map<String, String> decoded = new LinkedHashMap<String, String>();
        if (Texts.isBlank(encoded)) {
            return decoded;
        }
        String[] lines = encoded.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                continue;
            }
            int equals = line.indexOf('=');
            if (equals < 0) {
                throw new IllegalArgumentException(
                        "render metadata line " + (i + 1) + " is not key=value: " + line);
            }
            decoded.put(line.substring(0, equals), line.substring(equals + 1));
        }
        return decoded;
    }

    private static String join(List<String> lines) {
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(line);
        }
        return out.toString();
    }
}
