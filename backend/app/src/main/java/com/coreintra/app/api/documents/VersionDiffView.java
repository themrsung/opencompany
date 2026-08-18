package com.coreintra.app.api.documents;

import com.coreintra.compat.Immutables;
import java.util.ArrayList;
import java.util.List;

/**
 * What changed between two versions of a document.
 *
 * <h2>Two halves, honest about which is which</h2>
 *
 * <p>The <b>field</b> half always works: field values are extracted into typed
 * rows on every save, so "the amount went from 1,400,000 to 1,650,000" is
 * answerable for a DOCX, a HWPX and an mdv alike, and it is the half a 결재
 * reviewer actually needs.
 *
 * <p>The <b>body</b> half only works for mdv, and this says so rather than
 * returning an empty diff that reads as "nothing changed" (§6.10). For the other
 * formats the truthful comparison is the content hash: same hash, same bytes;
 * different hash, something changed that this endpoint cannot describe line by
 * line.
 */
public class VersionDiffView {

    /** One field's fate between the two versions. */
    public static class FieldChange {
        private final String fieldId;
        private final String change;
        private final FieldValueView before;
        private final FieldValueView after;

        FieldChange(String fieldId, String change, FieldValueView before, FieldValueView after) {
            this.fieldId = fieldId;
            this.change = change;
            this.before = before;
            this.after = after;
        }

        public String getFieldId() {
            return fieldId;
        }

        /** {@code added}, {@code removed} or {@code changed}. Unchanged fields are absent. */
        public String getChange() {
            return change;
        }

        /** Null when the field was added. A blank field has no row at all. */
        public FieldValueView getBefore() {
            return before;
        }

        /** Null when the field was cleared. */
        public FieldValueView getAfter() {
            return after;
        }
    }

    /** The line-by-line half. */
    public static class Body {
        private final boolean available;
        private final String reason;
        private final boolean summarised;
        private final boolean truncated;
        private final int addedLines;
        private final int removedLines;
        private final List<TextDiff.Line> lines;

        Body(boolean available, String reason, TextDiff.Result result) {
            this.available = available;
            this.reason = reason;
            this.summarised = result != null && result.summarised();
            this.truncated = result != null && result.truncated();
            this.addedLines = result == null ? 0 : result.added();
            this.removedLines = result == null ? 0 : result.removed();
            this.lines = result == null
                    ? Immutables.<TextDiff.Line>listOf()
                    : Immutables.copyOf(result.lines());
        }

        /** False for every format but mdv, with {@link #getReason()} saying why. */
        public boolean isAvailable() {
            return available;
        }

        public String getReason() {
            return reason;
        }

        /** True when the documents were too large to compare line by line. */
        public boolean isSummarised() {
            return summarised;
        }

        /** True when the comparison was computed but only part of it is returned. */
        public boolean isTruncated() {
            return truncated;
        }

        public int getAddedLines() {
            return addedLines;
        }

        public int getRemovedLines() {
            return removedLines;
        }

        public List<TextDiff.Line> getLines() {
            return lines;
        }
    }

    private final String documentId;
    private final int fromVersion;
    private final int toVersion;
    private final String fromFormat;
    private final String toFormat;
    private final String fromSha256;
    private final String toSha256;
    private final boolean identicalBytes;
    private final Body body;
    private final List<FieldChange> fields;

    VersionDiffView(String documentId, int fromVersion, int toVersion, String fromFormat,
            String toFormat, String fromSha256, String toSha256, Body body,
            List<FieldChange> fields) {
        this.documentId = documentId;
        this.fromVersion = fromVersion;
        this.toVersion = toVersion;
        this.fromFormat = fromFormat;
        this.toFormat = toFormat;
        this.fromSha256 = fromSha256;
        this.toSha256 = toSha256;
        this.identicalBytes = fromSha256 != null && fromSha256.equals(toSha256);
        this.body = body;
        this.fields = Immutables.copyOf(fields == null ? new ArrayList<FieldChange>() : fields);
    }

    public String getDocumentId() {
        return documentId;
    }

    /** The earlier version, the one being compared against. */
    public int getFromVersion() {
        return fromVersion;
    }

    public int getToVersion() {
        return toVersion;
    }

    public String getFromFormat() {
        return fromFormat;
    }

    public String getToFormat() {
        return toFormat;
    }

    public String getFromSha256() {
        return fromSha256;
    }

    public String getToSha256() {
        return toSha256;
    }

    /** Same content address, same bytes. Deduplication is a property, not a bug. */
    public boolean isIdenticalBytes() {
        return identicalBytes;
    }

    public Body getBody() {
        return body;
    }

    /** Only the fields that differ. An unchanged field is absent, not listed as equal. */
    public List<FieldChange> getFields() {
        return fields;
    }
}
