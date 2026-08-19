package com.coreintra.app.api.documents;

import com.coreintra.documents.entity.DocumentVersionEntity;
import java.util.function.Function;

/**
 * One version of a document.
 *
 * <p>Versions are append-only: {@code supersedesVersionNo} names the one this
 * replaced rather than the row being rewritten, so the trail shows both the
 * mistake and the fix and an approval that pointed at version 3 still resolves
 * to what was approved.
 *
 * <p>{@code blobSha256} is the content address, and it is the honest answer to
 * "did this change?" for the formats that cannot be diffed (§6.10): two versions
 * with the same hash are the same bytes, and two with different hashes differ in
 * a way only the mdv path can describe line by line.
 */
public class DocumentVersionView {

    static final Function<DocumentVersionEntity, DocumentVersionView> MAPPER =
            new Function<DocumentVersionEntity, DocumentVersionView>() {
                @Override
                public DocumentVersionView apply(DocumentVersionEntity version) {
                    return from(version);
                }
            };

    /** Newest last: the cursor walks the history forwards, and 9 sorts before 10. */
    static final DocumentPages.Keys<DocumentVersionEntity> KEYS =
            new DocumentPages.Keys<DocumentVersionEntity>() {
                @Override
                public String sortKey(DocumentVersionEntity version) {
                    return DocumentPages.number(version.versionNo().intValue());
                }

                @Override
                public String id(DocumentVersionEntity version) {
                    return version.documentId() + "#" + version.versionNo();
                }
            };

    private final String documentId;
    private final int versionNo;
    private final String format;
    private final String blobSha256;
    private final String authoredAt;
    private final String authorAccountId;
    private final Integer supersedesVersionNo;
    private final String createdAt;

    DocumentVersionView(DocumentVersionEntity version) {
        this.documentId = version.documentId();
        this.versionNo = version.versionNo().intValue();
        this.format = version.format().name();
        this.blobSha256 = version.blobSha256();
        this.authoredAt = version.authoredAt() == null ? null : version.authoredAt().toWireString();
        this.authorAccountId = version.authorAccountId();
        this.supersedesVersionNo = version.supersedesVersionNo();
        this.createdAt = String.valueOf(version.createdAt());
    }

    public static DocumentVersionView from(DocumentVersionEntity version) {
        return new DocumentVersionView(version);
    }

    public String getDocumentId() {
        return documentId;
    }

    public int getVersionNo() {
        return versionNo;
    }

    /** DOCX, HWPX, HWP or MDV — what the stored bytes are, not what they can export to. */
    public String getFormat() {
        return format;
    }

    public String getBlobSha256() {
        return blobSha256;
    }

    /**
     * When it was written, in business time.
     *
     * <p>Wire form {@code YYYY-MM-DDT[-]HH:MM:SS.mmm}: no timezone, no trailing
     * Z, and never ordered lexically — the offset may read {@code 26:30} for a
     * document typed after midnight on a 72-hour day (ADR 0002).
     */
    public String getAuthoredAt() {
        return authoredAt;
    }

    public String getAuthorAccountId() {
        return authorAccountId;
    }

    /** Null on the first version. Otherwise the version this one replaced. */
    public Integer getSupersedesVersionNo() {
        return supersedesVersionNo;
    }

    /** UTC, recorded separately from the business instant and never conflated with it. */
    public String getCreatedAt() {
        return createdAt;
    }
}
