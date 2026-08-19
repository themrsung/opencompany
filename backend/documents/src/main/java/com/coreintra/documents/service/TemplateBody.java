package com.coreintra.documents.service;

import com.coreintra.compat.Texts;
import com.coreintra.documents.entity.DocumentFormat;

/**
 * One language's body of a template version, on its way in.
 *
 * <p>Bodies are published together (brief 6.8): a client who edits the Korean wording
 * must not silently create a version whose English body is a different document. This
 * type exists so that "together" is expressible in one call rather than being a rule
 * somebody has to remember.
 */
public final class TemplateBody {

    private final String locale;

    private final DocumentFormat format;

    private final byte[] content;

    private final String filename;

    public TemplateBody(String locale, DocumentFormat format, byte[] content, String filename) {
        if (Texts.isBlank(locale)) {
            throw new IllegalArgumentException("a template body is in a language; name it");
        }
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException(
                    "template body for locale " + locale + " is empty; an empty template is a "
                    + "blank page every document drafted from it inherits");
        }
        this.locale = locale;
        this.format = format;
        this.content = content.clone();
        this.filename = filename;
    }

    public String locale() {
        return locale;
    }

    public DocumentFormat format() {
        return format;
    }

    /** A copy: the caller's array and the stored bytes must not be the same object. */
    public byte[] content() {
        return content.clone();
    }

    public String filename() {
        return filename;
    }
}
