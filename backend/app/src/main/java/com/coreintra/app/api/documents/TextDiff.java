package com.coreintra.app.api.documents;

import com.coreintra.compat.Immutables;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A line diff of two mdv documents.
 *
 * <h2>Why only mdv</h2>
 *
 * <p>§6.10 says it outright: mdv is the only format here that can be diffed for
 * real, and the version-compare feature is a reason to prefer it for anything
 * that gets revised. A DOCX is a ZIP of XML whose bytes change when nothing
 * visible does — a rewritten {@code w:rsid}, a re-ordered relationship — so a
 * textual diff of it reports noise, and a semantic one is a project rather than
 * a method. mdv is canonicalised by {@code mdv fmt} on save, so two versions of
 * it differ exactly where the author changed something.
 *
 * <h2>Why it stops</h2>
 *
 * <p>The LCS table is quadratic. A pair of thousand-line documents is four
 * million cells, which is fine; a pair of hundred-thousand-line ones is not, and
 * a request thread computing one is a request thread nobody else can have. Past
 * the cap this reports the counts and says it stopped, which is a true answer,
 * rather than a plausible-looking diff of the first page.
 */
final class TextDiff {

    /** Beyond this many cells the diff is summarised instead of computed. */
    private static final int MAX_CELLS = 4_000_000;

    /** Beyond this many emitted lines the result is truncated, and says so. */
    private static final int MAX_LINES = 4_000;

    private TextDiff() {
    }

    /** One line of the comparison. */
    static final class Line {
        private final String op;
        private final String text;
        private final Integer beforeLine;
        private final Integer afterLine;

        Line(String op, String text, Integer beforeLine, Integer afterLine) {
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

    /** The comparison, and whether it is complete. */
    static final class Result {
        private final List<Line> lines;
        private final int added;
        private final int removed;
        private final boolean summarised;
        private final boolean truncated;

        Result(List<Line> lines, int added, int removed, boolean summarised, boolean truncated) {
            this.lines = Immutables.copyOf(lines);
            this.added = added;
            this.removed = removed;
            this.summarised = summarised;
            this.truncated = truncated;
        }

        List<Line> lines() {
            return lines;
        }

        int added() {
            return added;
        }

        int removed() {
            return removed;
        }

        /** True when the documents were too big to diff line by line. */
        boolean summarised() {
            return summarised;
        }

        /** True when the diff was computed but not all of it is returned. */
        boolean truncated() {
            return truncated;
        }
    }

    static List<String> lines(byte[] utf8) {
        String text = new String(utf8, com.coreintra.compat.Texts.UTF_8);
        List<String> lines = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines.add(trimCarriageReturn(text.substring(start, i)));
                start = i + 1;
            }
        }
        if (start < text.length()) {
            lines.add(trimCarriageReturn(text.substring(start)));
        }
        return lines;
    }

    static Result of(List<String> before, List<String> after) {
        int prefix = 0;
        while (prefix < before.size() && prefix < after.size()
                && before.get(prefix).equals(after.get(prefix))) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < before.size() - prefix && suffix < after.size() - prefix
                && before.get(before.size() - 1 - suffix).equals(after.get(after.size() - 1 - suffix))) {
            suffix++;
        }
        List<String> leftMiddle = before.subList(prefix, before.size() - suffix);
        List<String> rightMiddle = after.subList(prefix, after.size() - suffix);

        if ((long) leftMiddle.size() * (long) rightMiddle.size() > MAX_CELLS) {
            return new Result(Immutables.<Line>listOf(), rightMiddle.size(), leftMiddle.size(),
                    true, false);
        }

        List<Line> middle = walk(leftMiddle, rightMiddle, prefix);
        int added = 0;
        int removed = 0;
        for (Line line : middle) {
            if ("added".equals(line.getOp())) {
                added++;
            } else if ("removed".equals(line.getOp())) {
                removed++;
            }
        }
        boolean truncated = middle.size() > MAX_LINES;
        return new Result(truncated ? middle.subList(0, MAX_LINES) : middle, added, removed,
                false, truncated);
    }

    /**
     * The classic LCS walk.
     *
     * <p>Only the changed region is walked — the common prefix and suffix were
     * trimmed first, and they are the bulk of any real revision — so a one-line
     * edit to a long document costs almost nothing.
     */
    private static List<Line> walk(List<String> left, List<String> right, int offset) {
        int rows = left.size();
        int columns = right.size();
        int[][] lengths = new int[rows + 1][columns + 1];
        for (int i = rows - 1; i >= 0; i--) {
            for (int j = columns - 1; j >= 0; j--) {
                lengths[i][j] = left.get(i).equals(right.get(j))
                        ? lengths[i + 1][j + 1] + 1
                        : Math.max(lengths[i + 1][j], lengths[i][j + 1]);
            }
        }

        List<Line> out = new ArrayList<Line>();
        int i = 0;
        int j = 0;
        while (i < rows && j < columns) {
            if (left.get(i).equals(right.get(j))) {
                out.add(new Line("context", left.get(i), Integer.valueOf(offset + i + 1),
                        Integer.valueOf(offset + j + 1)));
                i++;
                j++;
            } else if (lengths[i + 1][j] >= lengths[i][j + 1]) {
                out.add(new Line("removed", left.get(i), Integer.valueOf(offset + i + 1), null));
                i++;
            } else {
                out.add(new Line("added", right.get(j), null, Integer.valueOf(offset + j + 1)));
                j++;
            }
        }
        while (i < rows) {
            out.add(new Line("removed", left.get(i), Integer.valueOf(offset + i + 1), null));
            i++;
        }
        while (j < columns) {
            out.add(new Line("added", right.get(j), null, Integer.valueOf(offset + j + 1)));
            j++;
        }
        return Collections.unmodifiableList(out);
    }

    private static String trimCarriageReturn(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }
}
