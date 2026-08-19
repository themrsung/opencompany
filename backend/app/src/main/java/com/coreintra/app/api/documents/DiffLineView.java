package com.coreintra.app.api.documents;

/**
 * One line of a version comparison.
 *
 * <p>A top-level type with a name of its own rather than a class nested inside
 * the differ: it is part of the wire contract, the generated TypeScript client
 * names it, and {@code Line} would be both ambiguous in a 150-schema document
 * and meaningless in a client's import list.
 *
 * <p>Both line numbers are carried because a reviewer reading a change needs to
 * find it in both documents; the missing one says which side the line is absent
 * from, which is the same fact as the operation and is easier to render.
 */
public class DiffLineView {

    private final String op;
    private final String text;
    private final Integer beforeLine;
    private final Integer afterLine;

    DiffLineView(String op, String text, Integer beforeLine, Integer afterLine) {
        this.op = op;
        this.text = text;
        this.beforeLine = beforeLine;
        this.afterLine = afterLine;
    }

    /** {@code context}, {@code added} or {@code removed}. */
    public String getOp() {
        return op;
    }

    public String getText() {
        return text;
    }

    /** 1-based line number in the earlier version, or null for an added line. */
    public Integer getBeforeLine() {
        return beforeLine;
    }

    /** 1-based line number in the later version, or null for a removed line. */
    public Integer getAfterLine() {
        return afterLine;
    }
}
