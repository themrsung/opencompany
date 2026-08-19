package com.coreintra.documents.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Immutables;
import com.coreintra.documents.blob.BlobRef;
import com.coreintra.documents.entity.DocumentFormat;
import com.coreintra.documents.entity.DocumentTemplateBodyEntity;
import com.coreintra.documents.entity.DocumentTemplateEntity;
import com.coreintra.documents.entity.DocumentTemplateVersionEntity;
import com.coreintra.documents.entity.TemplateVersionId;
import com.coreintra.documents.ooxml.OoxmlPackage;
import com.coreintra.documents.repository.DocumentTemplateBodyRepository;
import com.coreintra.documents.repository.DocumentTemplateRepository;
import com.coreintra.documents.repository.DocumentTemplateVersionRepository;
import com.coreintra.documents.schema.DocumentFieldSchema;

/**
 * Publishing, forking and restoring 서식.
 *
 * <p>Nothing here edits a published version, because a submitted document promises to
 * render identically forever and can only keep that promise if the version it names never
 * moves. Every change - an edit, a fork, a restore to factory state - is a new version
 * naming the one it supersedes.
 *
 * <p>Publishing cross-validates the manifest against the document itself, naming every
 * field that disagrees. That check is the reason the two artefacts are allowed to exist
 * separately at all (brief 6.1).
 */
@Service
public class TemplateService {

    private final DocumentTemplateRepository templates;

    private final DocumentTemplateVersionRepository versions;

    private final DocumentTemplateBodyRepository bodies;

    private final BlobService blobs;

    public TemplateService(DocumentTemplateRepository templates,
            DocumentTemplateVersionRepository versions, DocumentTemplateBodyRepository bodies,
            BlobService blobs) {
        this.templates = templates;
        this.versions = versions;
        this.bodies = bodies;
        this.blobs = blobs;
    }

    /** A template family with no versions yet. It cannot be drafted from until one lands. */
    @Transactional
    public DocumentTemplateEntity create(String companyId, String code, String documentType,
            String nameKo, String nameEn, String createdByAccountId) {
        Optional<DocumentTemplateEntity> clash = templates.findByCompanyIdAndCode(companyId, code);
        if (clash.isPresent()) {
            throw new IllegalArgumentException(
                    "template code \"" + code + "\" is already used by \"" + clash.get().nameKo()
                    + "\". Codes are the stable handle a seeded template is found by, so they "
                    + "cannot be reused.");
        }
        DocumentTemplateEntity template = new DocumentTemplateEntity(
                UUID.randomUUID().toString(), companyId, code, documentType, nameKo,
                createdByAccountId);
        template.setNameEn(nameEn);
        return templates.save(template);
    }

    /**
     * Publishes the next version: one manifest, one body per language.
     *
     * <p>Every DOCX body is checked against the manifest before anything is written. A
     * manifest field with no control renders an input that saves into nothing; a control
     * with no manifest entry is a field nobody can fill in and it prints blank on an
     * approved document. Both are named, all at once, so an author fixing five mismatches
     * does not have to save five times to find them.
     *
     * @throws DocumentFieldSchema.SchemaMismatchException naming every field that disagrees
     * @throws IllegalArgumentException if no DOCX body is supplied - DOCX is the canonical
     *         form, and a version that exists only as HWPX cannot be validated at all
     */
    @Transactional
    public DocumentTemplateVersionEntity publishVersion(String templateId,
            DocumentFieldSchema schema, List<TemplateBody> newBodies, String publishedByAccountId,
            BusinessInstant publishedAt) {
        DocumentTemplateEntity template = require(templateId);
        if (newBodies == null || newBodies.isEmpty()) {
            throw new IllegalArgumentException("a template version needs at least one body");
        }
        boolean sawDocx = false;
        boolean approvalBlock = false;
        for (TemplateBody body : newBodies) {
            if (body.format() != DocumentFormat.DOCX) {
                continue;
            }
            sawDocx = true;
            byte[] documentPart = OoxmlPackage.read(body.content()).documentPart();
            DocumentFieldSchema.ValidationResult result = schema.validateAgainst(documentPart);
            result.orThrow();
            approvalBlock = approvalBlock || result.hasApprovalBlock();
        }
        if (!sawDocx) {
            throw new IllegalArgumentException(
                    "a template version needs a DOCX body. DOCX is the canonical form and the "
                    + "only one the field manifest can be cross-validated against, so a version "
                    + "published without one could never be checked.");
        }

        Optional<DocumentTemplateVersionEntity> latest =
                versions.findFirstByTemplateIdOrderByVersionNoDesc(templateId);
        int nextVersion = latest.isPresent() ? latest.get().versionNo().intValue() + 1 : 1;
        Integer supersedes = latest.isPresent() ? latest.get().versionNo() : null;

        DocumentTemplateVersionEntity version = versions.save(new DocumentTemplateVersionEntity(
                templateId, nextVersion, FieldSchemaCodec.encode(schema), approvalBlock, supersedes,
                publishedByAccountId, publishedAt));

        for (TemplateBody body : newBodies) {
            BlobRef ref = blobs.store(body.content(), contentTypeOf(body.format()), body.filename());
            bodies.save(new DocumentTemplateBodyEntity(templateId, nextVersion, body.locale(),
                    ref.sha256(), body.format()));
        }

        template.publishVersion(nextVersion);
        templates.save(template);
        return version;
    }

    /**
     * Copies a template's latest version into a new template the client owns.
     *
     * <p>A fork rather than an edit, because a seeded template must stay restorable: a
     * client who edits the shipped 휴가신청서 in place and then wants the original back has
     * nothing to go back to.
     */
    @Transactional
    public DocumentTemplateEntity fork(String sourceTemplateId, String newCode, String newNameKo,
            String byAccountId, BusinessInstant at) {
        DocumentTemplateEntity source = require(sourceTemplateId);
        DocumentTemplateVersionEntity sourceVersion =
                versions.findFirstByTemplateIdOrderByVersionNoDesc(sourceTemplateId)
                        .orElseThrow(() -> new IllegalStateException(
                                "template " + sourceTemplateId + " has no published version to fork"));

        DocumentTemplateEntity fork = create(source.companyId(), newCode, source.documentType(),
                newNameKo, source.nameEn(), byAccountId);
        copyVersionInto(fork.id(), sourceTemplateId, sourceVersion, 1, null, byAccountId, at);
        fork.publishVersion(1);
        return templates.save(fork);
    }

    /**
     * Restores a seeded template to factory state.
     *
     * <p>Version 1 of a built-in template is what shipped, by construction: the seed writes
     * it and nothing else may. Restoring re-publishes that content as a NEW version rather
     * than rewinding, so the client's edits remain in the history and any document drafted
     * from them still renders.
     *
     * @throws IllegalArgumentException if the template was not seeded - there is no factory
     *         state to return a client's own template to, and silently picking version 1
     *         would discard their work while claiming to restore it
     */
    @Transactional
    public DocumentTemplateVersionEntity restoreToFactory(String templateId, String byAccountId,
            BusinessInstant at) {
        DocumentTemplateEntity template = require(templateId);
        if (!template.isBuiltIn()) {
            throw new IllegalArgumentException(
                    "\"" + template.nameKo() + "\" was created by this client, so it has no factory "
                    + "state. Restoring would mean discarding their work and calling it a restore.");
        }
        DocumentTemplateVersionEntity factory = versions
                .findById(new TemplateVersionId(templateId, Integer.valueOf(1)))
                .orElseThrow(() -> new IllegalStateException(
                        "built-in template " + templateId + " has no version 1; the seed is missing"));
        DocumentTemplateVersionEntity latest =
                versions.findFirstByTemplateIdOrderByVersionNoDesc(templateId).get();
        int nextVersion = latest.versionNo().intValue() + 1;

        DocumentTemplateVersionEntity restored = copyVersionInto(templateId, templateId, factory,
                nextVersion, latest.versionNo(), byAccountId, at);
        template.publishVersion(nextVersion);
        templates.save(template);
        return restored;
    }

    /** What a drafter may start from. */
    @Transactional(readOnly = true)
    public List<DocumentTemplateEntity> list(String companyId) {
        return templates.findByCompanyIdAndRetiredAtIsNull(companyId);
    }

    @Transactional(readOnly = true)
    public Optional<DocumentTemplateEntity> find(String templateId) {
        return templates.findById(templateId);
    }

    @Transactional(readOnly = true)
    public List<DocumentTemplateEntity> listFor(String companyId, String documentType) {
        return templates.findByCompanyIdAndDocumentTypeAndRetiredAtIsNullAndActiveTrue(
                companyId, documentType);
    }

    @Transactional(readOnly = true)
    public List<DocumentTemplateVersionEntity> versionsOf(String templateId) {
        return versions.findByTemplateIdOrderByVersionNoDesc(templateId);
    }

    /** The manifest as published with that version, decoded. */
    @Transactional(readOnly = true)
    public DocumentFieldSchema schemaOf(String templateId, int versionNo) {
        return FieldSchemaCodec.decode(requireVersion(templateId, versionNo).fieldSchema());
    }

    /**
     * The body for a locale, falling back to Korean.
     *
     * <p>Korean is the default locale on a fresh install, so a template published in
     * Korean alone is complete; one published in English alone is not, and the fallback
     * makes that asymmetry explicit rather than returning nothing.
     */
    @Transactional(readOnly = true)
    public Optional<DocumentTemplateBodyEntity> bodyOf(String templateId, int versionNo,
            String locale) {
        Optional<DocumentTemplateBodyEntity> exact = bodies.findByTemplateIdAndVersionNoAndLocale(
                templateId, Integer.valueOf(versionNo), locale);
        if (exact.isPresent()) {
            return exact;
        }
        return bodies.findByTemplateIdAndVersionNoAndLocale(templateId, Integer.valueOf(versionNo),
                "ko");
    }

    @Transactional
    public void retire(String templateId, OffsetDateTime at) {
        DocumentTemplateEntity template = require(templateId);
        template.retire(at);
        templates.save(template);
    }

    private DocumentTemplateVersionEntity copyVersionInto(String targetTemplateId,
            String sourceTemplateId, DocumentTemplateVersionEntity source, int versionNo,
            Integer supersedes, String byAccountId, BusinessInstant at) {
        DocumentTemplateVersionEntity copy = versions.save(new DocumentTemplateVersionEntity(
                targetTemplateId, versionNo, source.fieldSchema(), source.hasApprovalBlock(),
                supersedes, byAccountId, at));
        List<DocumentTemplateBodyEntity> sourceBodies =
                bodies.findByTemplateIdAndVersionNo(sourceTemplateId, source.versionNo());
        List<DocumentTemplateBodyEntity> copied = new ArrayList<DocumentTemplateBodyEntity>();
        for (DocumentTemplateBodyEntity body : sourceBodies) {
            // The bytes are shared, not duplicated: content addressing means a fork of an
            // unedited template costs one row, not another copy of the docx.
            copied.add(new DocumentTemplateBodyEntity(targetTemplateId, versionNo, body.locale(),
                    body.blobSha256(), body.format()));
        }
        bodies.saveAll(Immutables.copyOf(copied));
        return copy;
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

    private DocumentTemplateEntity require(String templateId) {
        return templates.findById(templateId).orElseThrow(
                () -> new IllegalArgumentException("no such template: " + templateId));
    }

    private DocumentTemplateVersionEntity requireVersion(String templateId, int versionNo) {
        return versions
                .findById(new TemplateVersionId(templateId, Integer.valueOf(versionNo)))
                .orElseThrow(() -> new IllegalArgumentException(
                        "template " + templateId + " has no version " + versionNo));
    }
}
