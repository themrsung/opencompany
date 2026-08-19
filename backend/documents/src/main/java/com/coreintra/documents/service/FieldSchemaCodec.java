package com.coreintra.documents.service;

import java.util.ArrayList;
import java.util.List;

import com.coreintra.compat.Texts;
import com.coreintra.documents.schema.DocumentFieldSchema;
import com.coreintra.documents.schema.FieldDefinition;
import com.coreintra.documents.schema.FieldType;

/**
 * Encodes a field manifest as the text stored on a template version.
 *
 * <h2>Why not JSON</h2>
 *
 * <p>This column is read by a person during an incident and diffed by the version-compare
 * UI. One line per field, in declaration order, diffs to exactly the fields that changed;
 * pretty-printed JSON diffs to the punctuation around them, and minified JSON diffs to one
 * enormous line. The format is also stable under a Jackson upgrade, which matters for a
 * column that must still decode in five years against a document approved today.
 *
 * <p>Tab-separated, backslash-escaped: {@code tag TAB type TAB required TAB labelKo TAB
 * labelEn TAB helpKo TAB helpEn}. Labels are user text and can contain anything, so the
 * escaping is not optional - an unescaped tab in a Korean label would silently shift every
 * later column and rename the field.
 */
public final class FieldSchemaCodec {

    private static final char SEPARATOR = '\t';

    private FieldSchemaCodec() {
    }

    /** Renders a manifest for storage. Round-trips through {@link #decode(String)}. */
    public static String encode(DocumentFieldSchema schema) {
        StringBuilder out = new StringBuilder();
        for (FieldDefinition field : schema.fields()) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(escape(field.tag())).append(SEPARATOR);
            out.append(field.type().name()).append(SEPARATOR);
            out.append(field.isRequired() ? "required" : "optional").append(SEPARATOR);
            out.append(escape(field.labelKo())).append(SEPARATOR);
            out.append(escape(field.labelEn())).append(SEPARATOR);
            out.append(escape(field.helpKo())).append(SEPARATOR);
            out.append(escape(field.helpEn()));
        }
        return out.toString();
    }

    /**
     * Reads a stored manifest back.
     *
     * @throws IllegalArgumentException if a line is malformed, naming the line - a manifest
     *         that decodes to something plausible but wrong would validate documents against
     *         a schema nobody wrote
     */
    public static DocumentFieldSchema decode(String encoded) {
        List<FieldDefinition> fields = new ArrayList<FieldDefinition>();
        if (Texts.isBlank(encoded)) {
            return new DocumentFieldSchema(fields);
        }
        String[] lines = encoded.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\t", -1);
            if (parts.length != 7) {
                throw new IllegalArgumentException(
                        "stored field manifest line " + (i + 1) + " has " + parts.length
                        + " columns, not 7: " + line);
            }
            FieldType type;
            try {
                type = FieldType.valueOf(parts[1]);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "stored field manifest line " + (i + 1) + " names an unknown field type \""
                        + parts[1] + "\"", e);
            }
            fields.add(new FieldDefinition(unescape(parts[0]), type, unescape(parts[3]),
                    emptyToNull(unescape(parts[4])), "required".equals(parts[2]),
                    emptyToNull(unescape(parts[5])), emptyToNull(unescape(parts[6]))));
        }
        return new DocumentFieldSchema(fields);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\') {
                out.append("\\\\");
            } else if (c == '\t') {
                out.append("\\t");
            } else if (c == '\n') {
                out.append("\\n");
            } else if (c == '\r') {
                out.append("\\r");
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String unescape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                out.append(c);
                continue;
            }
            char next = value.charAt(++i);
            if (next == 't') {
                out.append('\t');
            } else if (next == 'n') {
                out.append('\n');
            } else if (next == 'r') {
                out.append('\r');
            } else {
                out.append(next);
            }
        }
        return out.toString();
    }
}
