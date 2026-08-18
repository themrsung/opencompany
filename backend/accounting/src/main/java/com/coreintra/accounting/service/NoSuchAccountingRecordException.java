package com.coreintra.accounting.service;

/**
 * The row named does not exist.
 *
 * <p>A subclass of {@link IllegalArgumentException} rather than a new hierarchy, so that a caller
 * which does not know about it — an MCP tool, the module SPI, the global exception handler — still
 * treats it as the bad argument it is and answers 400. What the subclass adds is the one
 * distinction an HTTP surface needs: "this id is not a book" is a 404, whereas "this amount is not
 * a decimal" is a 400, and a client retrying the first forever because it was told 400 is a
 * support call.
 *
 * <p>Deliberately says which kind of record and which id, and nothing else. Whether the caller was
 * <em>allowed</em> to know it does not exist is a separate question, and it is answered first:
 * every service authorises against the book before it looks anything up inside it.
 */
public class NoSuchAccountingRecordException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final String kind;
    private final String id;

    public NoSuchAccountingRecordException(String kind, String id) {
        super("there is no " + kind + " " + id);
        this.kind = kind;
        this.id = id;
    }

    public NoSuchAccountingRecordException(String kind, String id, String message) {
        super(message);
        this.kind = kind;
        this.id = id;
    }

    /** {@code book}, {@code entry}, {@code batch}, {@code account}, {@code 거래처}, … */
    public String kind() {
        return kind;
    }

    public String id() {
        return id;
    }
}
