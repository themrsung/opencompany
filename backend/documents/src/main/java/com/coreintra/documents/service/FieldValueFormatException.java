package com.coreintra.documents.service;

/**
 * A content control holds text the declared type cannot read.
 *
 * <p>Names the field and shows the text, because "invalid number" on a document with
 * forty fields is a bug report nobody can act on. This is a save-time error: the
 * document is not stored, so the projection can never disagree with the docx.
 */
public class FieldValueFormatException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient String fieldTag;

    private final transient String offendingText;

    public FieldValueFormatException(String fieldTag, String declaredType, String offendingText,
            Throwable cause) {
        super("field \"" + fieldTag + "\" is declared " + declaredType
                + " but holds \"" + offendingText + "\", which cannot be read as one", cause);
        this.fieldTag = fieldTag;
        this.offendingText = offendingText;
    }

    /** The {@code w:tag} of the field that could not be read. */
    public String fieldTag() {
        return fieldTag;
    }

    /** Exactly what was in the control. Shown to the user next to the field. */
    public String offendingText() {
        return offendingText;
    }
}
