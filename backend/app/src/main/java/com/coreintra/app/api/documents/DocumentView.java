package com.coreintra.app.api.documents;

import com.coreintra.app.api.http.ETags;
import com.coreintra.documents.entity.DocumentEntity;
import java.util.function.Function;

/**
 * A document header on the wire.
 *
 * <p>The bytes are not here and never will be: a document is fetched as a
 * download, so that a 20 MB HWP is not base64 inside a JSON object that a client
 * has to hold in memory twice. What is here is what a list view and an approval
 * inbox need — what it is, which template version it is pinned to, and which
 * version is current.
 *
 * <p>{@code currentVersionNo} doubles as the concurrency counter. Saving a
 * version bumps it, so an {@code If-Match} built from it fails exactly when
 * somebody else has saved since the caller read.
 */
public class DocumentView {

    static final Function<DocumentEntity, DocumentView> MAPPER =
            new Function<DocumentEntity, DocumentView>() {
                @Override
                public DocumentView apply(DocumentEntity document) {
                    return from(document);
                }
            };

    /**
     * Ordered by title, with the id breaking ties.
     *
     * <p>Not by {@code createdAt}: it is a UTC timestamp whose textual form
     * varies in fractional digits, so a lexical cursor over it would occasionally
     * compare {@code .4Z} against {@code .40Z} and drop a row. A title is stable
     * text, and the id makes the order total, which is all a cursor requires.
     */
    static final DocumentPages.Keys<DocumentEntity> KEYS =
            new DocumentPages.Keys<DocumentEntity>() {
                @Override
                public String sortKey(DocumentEntity document) {
                    return document.title() == null ? "" : document.title();
                }

                @Override
                public String id(DocumentEntity document) {
                    return document.id();
                }
            };

    private final String id;
    private final String companyId;
    private final String documentType;
    private final String title;
    private final Integer currentVersionNo;
    private final String templateId;
    private final Integer templateVersionNo;
    private final String createdByAccountId;
    private final String createdAt;
    private final String retiredAt;

    DocumentView(DocumentEntity document) {
        this.id = document.id();
        this.companyId = document.companyId();
        this.documentType = document.documentType();
        this.title = document.title();
        this.currentVersionNo = document.currentVersionNo();
        this.templateId = document.templateId();
        this.templateVersionNo = document.templateVersionNo();
        this.createdByAccountId = document.createdByAccountId();
        this.createdAt = String.valueOf(document.createdAt());
        this.retiredAt = document.retiredAt() == null ? null : String.valueOf(document.retiredAt());
    }

    public static DocumentView from(DocumentEntity document) {
        return new DocumentView(document);
    }

    /** The tag an {@code If-Match} on this document must carry. */
    public static String tagOf(DocumentEntity document) {
        Integer version = document.currentVersionNo();
        return ETags.of(document.id(), version == null ? 0L : version.longValue());
    }

    public String getId() {
        return id;
    }

    public String getCompanyId() {
        return companyId;
    }

    /** 지출결의서, 휴가신청서 — the template's document type, carried onto the document. */
    public String getDocumentType() {
        return documentType;
    }

    public String getTitle() {
        return title;
    }

    /** Null only for a document whose first version has not landed yet. */
    public Integer getCurrentVersionNo() {
        return currentVersionNo;
    }

    /** Null for a document that came from an upload rather than a template. */
    public String getTemplateId() {
        return templateId;
    }

    /**
     * The template version this document is pinned to, forever.
     *
     * <p>It does not follow the template. A document approved against v3 renders
     * against v3 whatever v7 says (§6.8).
     */
    public Integer getTemplateVersionNo() {
        return templateVersionNo;
    }

    public String getCreatedByAccountId() {
        return createdByAccountId;
    }

    /** UTC, and separate from every business instant on the document (ADR 0002). */
    public String getCreatedAt() {
        return createdAt;
    }

    /** Set when retired. The row stays: an approved document is evidence. */
    public String getRetiredAt() {
        return retiredAt;
    }

    public boolean isRetired() {
        return retiredAt != null;
    }
}
