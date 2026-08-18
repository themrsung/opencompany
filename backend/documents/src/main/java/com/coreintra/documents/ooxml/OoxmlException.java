package com.coreintra.documents.ooxml;

/**
 * A document could not be read or written.
 *
 * <p>Messages here reach users who uploaded a file, so they say what to do
 * about it rather than naming an internal cause.
 */
public class OoxmlException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public OoxmlException(String message) {
        super(message);
    }

    public OoxmlException(String message, Throwable cause) {
        super(message, cause);
    }
}
