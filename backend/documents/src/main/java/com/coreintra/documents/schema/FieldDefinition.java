package com.coreintra.documents.schema;

import com.coreintra.compat.Texts;
import java.io.Serializable;

/**
 * One typed field in a template's manifest.
 *
 * <p>{@link #tag()} is the {@code w:sdt} tag in the docx. It is the join between
 * the manifest and the document, so it must match exactly — cross-validation on
 * save exists to catch the case where it does not.
 */
public final class FieldDefinition implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String tag;
    private final FieldType type;
    private final String labelKo;
    private final String labelEn;
    private final boolean required;
    private final String helpKo;
    private final String helpEn;

    public FieldDefinition(String tag, FieldType type, String labelKo, String labelEn,
            boolean required, String helpKo, String helpEn) {
        if (Texts.isBlank(tag)) {
            throw new IllegalArgumentException("a field needs a tag; it is the join to the docx");
        }
        if (type == null) {
            throw new NullPointerException("type");
        }
        if (Texts.isBlank(labelKo)) {
            // Korean is the default locale on a fresh install, so a field with
            // only an English label renders as a blank caption for most users.
            throw new IllegalArgumentException(
                    "field " + tag + " needs a Korean label; Korean is the default locale");
        }
        this.tag = Texts.strip(tag);
        this.type = type;
        this.labelKo = labelKo;
        this.labelEn = labelEn;
        this.required = required;
        this.helpKo = helpKo;
        this.helpEn = helpEn;
    }

    public static FieldDefinition of(String tag, FieldType type, String labelKo, String labelEn) {
        return new FieldDefinition(tag, type, labelKo, labelEn, false, null, null);
    }

    public static FieldDefinition required(String tag, FieldType type, String labelKo,
            String labelEn) {
        return new FieldDefinition(tag, type, labelKo, labelEn, true, null, null);
    }

    /** The {@code w:sdt} tag. The join between manifest and document. */
    public String tag() {
        return tag;
    }

    public FieldType type() {
        return type;
    }

    /** Field LABELS are translatable. Field DATA is not. */
    public String labelKo() {
        return labelKo;
    }

    public String labelEn() {
        return labelEn;
    }

    public String label(String locale) {
        if ("en".equals(locale) && Texts.hasText(labelEn)) {
            return labelEn;
        }
        return labelKo;
    }

    public boolean isRequired() {
        return required;
    }

    public String helpKo() {
        return helpKo;
    }

    public String helpEn() {
        return helpEn;
    }

    @Override
    public String toString() {
        return tag + " (" + type + ")";
    }
}
