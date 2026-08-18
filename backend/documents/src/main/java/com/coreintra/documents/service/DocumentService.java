package com.coreintra.documents.service;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.documents.blob.BlobRef;
import com.coreintra.documents.entity.DocumentEntity;
import com.coreintra.documents.entity.DocumentFieldValueEntity;
import com.coreintra.documents.entity.DocumentFormat;
import com.coreintra.documents.entity.DocumentTemplateBodyEntity;
import com.coreintra.documents.entity.DocumentTemplateEntity;
import com.coreintra.documents.entity.DocumentVersionEntity;
import com.coreintra.documents.ooxml.ContentControls;
import com.coreintra.documents.ooxml.OoxmlPackage;
import com.coreintra.documents.repository.DocumentFieldValueRepository;
import com.coreintra.documents.repository.DocumentRepository;
import com.coreintra.documents.repository.DocumentVersionRepository;
import com.coreintra.documents.schema.DocumentFieldSchema;

/**
 * Creating documents, versioning them, and keeping the queryable projection honest.
 *
 * <p>Two invariants live here and nowhere else.
 *
 * <p><b>A version is never rewritten.</b> Saving produces the next version naming the one
 * it supersedes, so the trail shows both the mistake and the fix, and an approval that
 * pointed at version 3 still resolves to what was approved.
 *
 * <p><b>The manifest and the document agree, or the save fails.</b> Cross-validation runs
 * before anything is stored, naming every field that disagrees. Checking at export time
 * instead would mean discovering it on a document somebody already approved.
 */
@Service
public class DocumentService {

    private final DocumentRepository documents;

    private final DocumentVersionRepository versions;

    private final DocumentFieldValueRepository fieldValues;

    private final TemplateService templates;

    private final BlobService blobs;

    public DocumentService(DocumentRepository documents, DocumentVersionRepository versions,
            DocumentFieldValueRepository fieldValues, TemplateService templates, BlobService blobs) {
        this.documents = documents;
        this.versions = versions;
        this.fieldValues = fieldValues;
        this.templates = templates;
        this.blobs = blobs;
    }

    /**
     * Starts a document from a published template version, pinned to that version forever.
     *
     * <p>The pin is the point. A document approved against v3 renders against v3 for the
     * rest of its life, whatever v7 says - so the template can be edited freely without
     * rewriting history (brief 6.8).
     */
    @Transactional
    public DocumentEntity createFromTemplate(String companyId, String templateId, int templateVersionNo,
            String locale, String title, String byAccountId, BusinessInstant authoredAt,
            String defaultCurrencyCode) {
        DocumentTemplateEntity template = templates.find(templateId).orElseThrow(
                () -> new IllegalArgumentException("no such template: " + templateId));
        if (!template.companyId().equals(companyId)) {
            // Templates are company-scoped. Drafting across the boundary would put one
            // client's wording, and their field manifest, inside another's document.
            throw new IllegalArgumentException(
                    "template " + templateId + " belongs to another company");
        }
        DocumentTemplateBodyEntity body = templates.bodyOf(templateId, templateVersionNo, locale)
                .orElseThrow(() -> new IllegalArgumentException(
                        "template " + templateId + " v" + templateVersionNo
                        + " has no body for locale \"" + locale + "\" and no Korean fallback"));

        DocumentEntity document = documents.save(new DocumentEntity(UUID.randomUUID().toString(),
                companyId, template.documentType(), title, templateId,
                Integer.valueOf(templateVersionNo), byAccountId));

        // The first version IS the template body, byte for byte. Content addressing means
        // that costs a row rather than a copy, and it means a document nobody has edited
        // is provably identical to the template it came from.
        writeVersion(document, blobs.contentOf(body.blobSha256()), body.format(), byAccountId,
                authoredAt, defaultCurrencyCode);
        return document;
    }

    /** A document that came from an upload rather than a template. It has no pinned manifest. */
    @Transactional
    public DocumentEntity createFromUpload(String companyId, String documentType, String title,
            byte[] content, DocumentFormat format, String byAccountId, BusinessInstant authoredAt) {
        DocumentEntity document = documents.save(new DocumentEntity(UUID.randomUUID().toString(),
                companyId, documentType, title, null, null, byAccountId));
        writeVersion(document, content, format, byAccountId, authoredAt, null);
        return document;
    }

    /**
     * Writes the next version.
     *
     * @throws DocumentFieldSchema.SchemaMismatchException naming every field on which the
     *         pinned manifest and the document disagree
     * @throws FieldValueFormatException naming the field whose text does not fit its type
     * @throws IllegalStateException if the document has been retired
     */
    @Transactional
    public DocumentVersionEntity saveVersion(String documentId, byte[] content, DocumentFormat format,
            String authorAccountId, BusinessInstant authoredAt, String defaultCurrencyCode) {
        DocumentEntity document = require(documentId);
        if (document.isRetired()) {
            throw new IllegalStateException(
                    "document " + documentId + " was retired on " + document.retiredAt()
                    + " and takes no further versions");
        }
        return writeVersion(document, content, format, authorAccountId, authoredAt,
                defaultCurrencyCode);
    }

    @Transactional(readOnly = true)
    public Optional<DocumentVersionEntity> version(String documentId, int versionNo) {
        return versions.findByDocumentIdAndVersionNo(documentId, Integer.valueOf(versionNo));
    }

    /** Newest first. The history panel. */
    @Transactional(readOnly = true)
    public List<DocumentVersionEntity> versionsOf(String documentId) {
        return versions.findByDocumentIdOrderByVersionNoDesc(documentId);
    }

    /** The bytes of one version, exactly as they were written. */
    @Transactional(readOnly = true)
    public byte[] contentOf(String documentId, int versionNo) {
        DocumentVersionEntity version = version(documentId, versionNo).orElseThrow(
                () -> new IllegalArgumentException(
                        "document " + documentId + " has no version " + versionNo));
        return blobs.contentOf(version.blobSha256());
    }

    @Transactional(readOnly = true)
    public List<DocumentFieldValueEntity> fieldValuesOf(String documentId, int versionNo) {
        return fieldValues.findByDocumentIdAndVersionNo(documentId, Integer.valueOf(versionNo));
    }

    /**
     * The required fields still empty, for the submission gate.
     *
     * <p>Not checked on save: drafts are saved half-filled all day, and refusing to store
     * one would lose the user's work to enforce a rule that only applies at submission.
     */
    @Transactional(readOnly = true)
    public List<String> missingRequiredFields(String documentId, int versionNo) {
        DocumentEntity document = require(documentId);
        Optional<DocumentFieldSchema> schema = pinnedSchemaOf(document);
        if (!schema.isPresent()) {
            return Collections.emptyList();
        }
        return FieldValueExtractor.missingRequired(schema.get(),
                ContentControls.readValues(
                        OoxmlPackage.read(contentOf(documentId, versionNo)).documentPart()));
    }

    /** Retires the document. No hard delete: an approved document is evidence. */
    @Transactional
    public void retire(String documentId, OffsetDateTime at) {
        DocumentEntity document = require(documentId);
        document.retire(at);
        documents.save(document);
    }

    private DocumentVersionEntity writeVersion(DocumentEntity document, byte[] content,
            DocumentFormat format, String authorAccountId, BusinessInstant authoredAt,
            String defaultCurrencyCode) {
        Optional<DocumentFieldSchema> schema = pinnedSchemaOf(document);
        byte[] documentPart = null;
        if (format == DocumentFormat.DOCX && schema.isPresent()) {
            documentPart = OoxmlPackage.read(content).documentPart();
            // Named here, all at once, before a single byte is stored: a document whose
            // fields save into nothing must never reach the store at all.
            schema.get().validateAgainst(documentPart).orThrow();
        }

        Integer current = document.currentVersionNo();
        int nextVersion = current == null ? 1 : current.intValue() + 1;
        BlobRef ref = blobs.store(content, contentTypeOf(format), document.title());

        DocumentVersionEntity version = versions.save(new DocumentVersionEntity(document.id(),
                nextVersion, ref.sha256(), format, authoredAt, authorAccountId, current));

        if (documentPart != null) {
            fieldValues.saveAll(FieldValueExtractor.extract(document.id(), nextVersion, schema.get(),
                    ContentControls.readValues(documentPart),
                    defaultCurrencyCode));
        }

        document.headIsNow(nextVersion);
        documents.save(document);
        return version;
    }

    /** The manifest this document is bound to, or empty for an ad-hoc upload. */
    private Optional<DocumentFieldSchema> pinnedSchemaOf(DocumentEntity document) {
        if (document.templateId() == null || document.templateVersionNo() == null) {
            return Optional.empty();
        }
        return Optional.of(templates.schemaOf(document.templateId(),
                document.templateVersionNo().intValue()));
    }

    private static String contentTypeOf(DocumentFormat format) {
        if (format == DocumentFormat.DOCX) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if (format == DocumentFormat.HWPX) {
            return "application/hwp+zip";
        }
        if (format == DocumentFormat.HWP) {
            return "application/x-hwp";
        }
        return "text/vnd.mdv";
    }

    private DocumentEntity require(String documentId) {
        return documents.findById(documentId).orElseThrow(
                () -> new IllegalArgumentException("no such document: " + documentId));
    }
}
