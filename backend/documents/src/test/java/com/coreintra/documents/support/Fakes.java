package com.coreintra.documents.support;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;

import com.coreintra.documents.entity.ApprovalSnapshotEntity;
import com.coreintra.documents.entity.BlobEntity;
import com.coreintra.documents.entity.ConversionJobEntity;
import com.coreintra.documents.entity.DocumentEntity;
import com.coreintra.documents.entity.DocumentFieldValueEntity;
import com.coreintra.documents.entity.DocumentFieldValueId;
import com.coreintra.documents.entity.DocumentRenderEntity;
import com.coreintra.documents.entity.DocumentTemplateBodyEntity;
import com.coreintra.documents.entity.DocumentTemplateEntity;
import com.coreintra.documents.entity.DocumentTemplateVersionEntity;
import com.coreintra.documents.entity.DocumentVersionEntity;
import com.coreintra.documents.entity.DocumentVersionId;
import com.coreintra.documents.entity.FontEntity;
import com.coreintra.documents.entity.FontSubstitutionRuleEntity;
import com.coreintra.documents.entity.RenderFormat;
import com.coreintra.documents.entity.SignatureImageEntity;
import com.coreintra.documents.entity.SignatureImpressionUseEntity;
import com.coreintra.documents.entity.TemplateBodyId;
import com.coreintra.documents.entity.TemplateVersionId;
import com.coreintra.documents.repository.ApprovalSnapshotRepository;
import com.coreintra.documents.repository.BlobRepository;
import com.coreintra.documents.repository.ConversionJobRepository;
import com.coreintra.documents.repository.DocumentFieldValueRepository;
import com.coreintra.documents.repository.DocumentRenderRepository;
import com.coreintra.documents.repository.DocumentRepository;
import com.coreintra.documents.repository.DocumentTemplateBodyRepository;
import com.coreintra.documents.repository.DocumentTemplateRepository;
import com.coreintra.documents.repository.DocumentTemplateVersionRepository;
import com.coreintra.documents.repository.DocumentVersionRepository;
import com.coreintra.documents.repository.FontRepository;
import com.coreintra.documents.repository.FontSubstitutionRuleRepository;
import com.coreintra.documents.repository.SignatureImageRepository;
import com.coreintra.documents.repository.SignatureImpressionUseRepository;

/** In-memory stand-ins for every repository the services use. */
public final class Fakes {

    private Fakes() {
    }

    /** Reference counting is a view in the schema, so the fake keeps its own reference list. */
    public static final class Blobs extends InMemoryRepository<BlobEntity, String>
            implements BlobRepository {

        private final List<String> referenced = new ArrayList<String>();

        public Blobs() {
            super(BlobEntity::sha256);
        }

        /** Stands in for the {@code blob_reference} view. */
        public void markReferenced(String sha256) {
            referenced.add(sha256);
        }

        @Override
        public List<BlobEntity> findUnreferenced() {
            return filter(blob -> !referenced.contains(blob.sha256()) && !blob.isReaped());
        }

        @Override
        public List<BlobEntity> findByBytesReapedAtIsNull() {
            return filter(blob -> !blob.isReaped());
        }

        @Override
        public boolean isReferenced(String sha256) {
            return referenced.contains(sha256);
        }
    }

    public static final class Templates
            extends InMemoryRepository<DocumentTemplateEntity, String>
            implements DocumentTemplateRepository {

        public Templates() {
            super(DocumentTemplateEntity::id);
        }

        @Override
        public List<DocumentTemplateEntity>
                findByCompanyIdAndDocumentTypeAndRetiredAtIsNullAndActiveTrue(String companyId,
                        String documentType) {
            return filter(t -> t.companyId().equals(companyId)
                    && t.documentType().equals(documentType)
                    && t.retiredAt() == null && t.isActive());
        }

        @Override
        public List<DocumentTemplateEntity> findByCompanyIdAndRetiredAtIsNull(String companyId) {
            return filter(t -> t.companyId().equals(companyId) && t.retiredAt() == null);
        }

        @Override
        public Optional<DocumentTemplateEntity> findByCompanyIdAndCode(String companyId, String code) {
            return first(t -> t.companyId().equals(companyId) && t.code().equals(code));
        }
    }

    public static final class TemplateVersions
            extends InMemoryRepository<DocumentTemplateVersionEntity, TemplateVersionId>
            implements DocumentTemplateVersionRepository {

        public TemplateVersions() {
            super(v -> new TemplateVersionId(v.templateId(), v.versionNo()));
        }

        @Override
        public List<DocumentTemplateVersionEntity> findByTemplateIdOrderByVersionNoDesc(
                String templateId) {
            List<DocumentTemplateVersionEntity> found =
                    filter(v -> v.templateId().equals(templateId));
            Collections.sort(found, Comparator.comparing(
                    DocumentTemplateVersionEntity::versionNo).reversed());
            return found;
        }

        @Override
        public Optional<DocumentTemplateVersionEntity> findFirstByTemplateIdOrderByVersionNoDesc(
                String templateId) {
            List<DocumentTemplateVersionEntity> found =
                    findByTemplateIdOrderByVersionNoDesc(templateId);
            return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
        }
    }

    public static final class TemplateBodies
            extends InMemoryRepository<DocumentTemplateBodyEntity, TemplateBodyId>
            implements DocumentTemplateBodyRepository {

        public TemplateBodies() {
            super(b -> new TemplateBodyId(b.templateId(), b.versionNo(), b.locale()));
        }

        @Override
        public List<DocumentTemplateBodyEntity> findByTemplateIdAndVersionNo(String templateId,
                Integer versionNo) {
            return filter(b -> b.templateId().equals(templateId) && b.versionNo().equals(versionNo));
        }

        @Override
        public Optional<DocumentTemplateBodyEntity> findByTemplateIdAndVersionNoAndLocale(
                String templateId, Integer versionNo, String locale) {
            return first(b -> b.templateId().equals(templateId) && b.versionNo().equals(versionNo)
                    && b.locale().equals(locale));
        }
    }

    public static final class Documents extends InMemoryRepository<DocumentEntity, String>
            implements DocumentRepository {

        public Documents() {
            super(DocumentEntity::id);
        }

        @Override
        public List<DocumentEntity> findByCompanyIdAndRetiredAtIsNull(String companyId) {
            return filter(d -> d.companyId().equals(companyId) && d.retiredAt() == null);
        }

        @Override
        public List<DocumentEntity> findByCompanyIdAndDocumentTypeAndRetiredAtIsNull(
                String companyId, String documentType) {
            return filter(d -> d.companyId().equals(companyId)
                    && documentType.equals(d.documentType()) && d.retiredAt() == null);
        }

        @Override
        public List<DocumentEntity> findByTemplateIdAndTemplateVersionNo(String templateId,
                Integer versionNo) {
            return filter(d -> templateId.equals(d.templateId())
                    && versionNo.equals(d.templateVersionNo()));
        }
    }

    public static final class DocumentVersions
            extends InMemoryRepository<DocumentVersionEntity, DocumentVersionId>
            implements DocumentVersionRepository {

        public DocumentVersions() {
            super(v -> new DocumentVersionId(v.documentId(), v.versionNo()));
        }

        @Override
        public List<DocumentVersionEntity> findByDocumentIdOrderByVersionNoDesc(String documentId) {
            List<DocumentVersionEntity> found = filter(v -> v.documentId().equals(documentId));
            Collections.sort(found,
                    Comparator.comparing(DocumentVersionEntity::versionNo).reversed());
            return found;
        }

        @Override
        public Optional<DocumentVersionEntity> findFirstByDocumentIdOrderByVersionNoDesc(
                String documentId) {
            List<DocumentVersionEntity> found = findByDocumentIdOrderByVersionNoDesc(documentId);
            return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
        }

        @Override
        public Optional<DocumentVersionEntity> findByDocumentIdAndVersionNo(String documentId,
                Integer versionNo) {
            return first(v -> v.documentId().equals(documentId) && v.versionNo().equals(versionNo));
        }

        @Override
        public List<DocumentVersionEntity> findByBlobSha256(String blobSha256) {
            return filter(v -> v.blobSha256().equals(blobSha256));
        }
    }

    public static final class FieldValues
            extends InMemoryRepository<DocumentFieldValueEntity, DocumentFieldValueId>
            implements DocumentFieldValueRepository {

        public FieldValues() {
            super(v -> new DocumentFieldValueId(v.documentId(), v.versionNo(), v.fieldId()));
        }

        @Override
        public List<DocumentFieldValueEntity> findByDocumentIdAndVersionNo(String documentId,
                Integer versionNo) {
            return filter(v -> v.documentId().equals(documentId) && v.versionNo().equals(versionNo));
        }

        @Override
        public List<DocumentFieldValueEntity> findByFieldIdAndValueText(String fieldId,
                String valueText) {
            return filter(v -> v.fieldId().equals(fieldId) && valueText.equals(v.valueText()));
        }

        @Override
        public List<DocumentFieldValueEntity> findByFieldIdAndValueAmountGreaterThanEqual(
                String fieldId, BigDecimal amount) {
            return filter(v -> v.fieldId().equals(fieldId) && v.valueAmount() != null
                    && v.valueAmount().compareTo(amount) >= 0);
        }

        @Override
        public List<DocumentFieldValueEntity> findByFieldIdAndValueRefId(String fieldId,
                String refId) {
            return filter(v -> v.fieldId().equals(fieldId) && refId.equals(v.valueRefId()));
        }
    }

    public static final class Fonts extends InMemoryRepository<FontEntity, String>
            implements FontRepository {

        public Fonts() {
            super(FontEntity::id);
        }

        @Override
        public List<FontEntity> findInstalledFor(String companyId) {
            return filter(f -> f.retiredAt() == null
                    && (f.companyId() == null || f.companyId().equals(companyId)));
        }

        @Override
        public Optional<FontEntity> findByCompanyIdAndFamilyAndStyle(String companyId, String family,
                String style) {
            return first(f -> java.util.Objects.equals(companyId, f.companyId())
                    && f.family().equals(family) && f.style().equals(style));
        }

        @Override
        public List<FontEntity> findByBlobSha256AndRetiredAtIsNull(String blobSha256) {
            return filter(f -> f.blobSha256().equals(blobSha256) && f.retiredAt() == null);
        }
    }

    public static final class Substitutions
            extends InMemoryRepository<FontSubstitutionRuleEntity, String>
            implements FontSubstitutionRuleRepository {

        public Substitutions() {
            super(FontSubstitutionRuleEntity::id);
        }

        @Override
        public List<FontSubstitutionRuleEntity>
                findByCompanyIdOrderByScopeAscScopeKeyAscPositionAsc(String companyId) {
            List<FontSubstitutionRuleEntity> found = filter(r -> r.companyId().equals(companyId));
            Collections.sort(found, Comparator
                    .comparing((FontSubstitutionRuleEntity r) -> r.scope().name())
                    .thenComparing(FontSubstitutionRuleEntity::scopeKey)
                    .thenComparingInt(FontSubstitutionRuleEntity::position));
            return found;
        }

        @Override
        public List<FontSubstitutionRuleEntity>
                findByCompanyIdAndScopeAndScopeKeyOrderByPositionAsc(String companyId,
                        FontSubstitutionRuleEntity.Scope scope, String scopeKey) {
            List<FontSubstitutionRuleEntity> found = filter(r -> r.companyId().equals(companyId)
                    && r.scope() == scope && r.scopeKey().equals(scopeKey));
            Collections.sort(found, Comparator.comparingInt(FontSubstitutionRuleEntity::position));
            return found;
        }
    }

    public static final class Signatures
            extends InMemoryRepository<SignatureImageEntity, String>
            implements SignatureImageRepository {

        public Signatures() {
            super(SignatureImageEntity::id);
        }

        @Override
        public Optional<SignatureImageEntity> findByEmployeeIdAndKindAndRevokedAtIsNull(
                String employeeId, SignatureImageEntity.Kind kind) {
            return first(s -> s.employeeId().equals(employeeId) && s.kind() == kind
                    && s.revokedAt() == null);
        }

        @Override
        public List<SignatureImageEntity> findByCompanyIdAndRevokedAtIsNull(String companyId) {
            return filter(s -> s.companyId().equals(companyId) && s.revokedAt() == null);
        }

        /**
         * Reads the private field, exactly as the JPQL projection does.
         *
         * <p>There is no accessor to call - that is the design, not an oversight - so the
         * fake has to reach for the field the same way Hibernate does. That this is awkward
         * to write in a test is the feature working.
         */
        @Override
        public Optional<String> liveContentHashForRenderer(String signatureImageId) {
            Optional<SignatureImageEntity> found =
                    first(s -> s.id().equals(signatureImageId) && s.revokedAt() == null);
            if (!found.isPresent()) {
                return Optional.empty();
            }
            try {
                Field field = SignatureImageEntity.class.getDeclaredField("blobSha256");
                field.setAccessible(true);
                return Optional.ofNullable((String) field.get(found.get()));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("signature content hash field moved", e);
            }
        }
    }

    public static final class SignatureUses
            extends InMemoryRepository<SignatureImpressionUseEntity, String>
            implements SignatureImpressionUseRepository {

        public SignatureUses() {
            super(SignatureImpressionUseEntity::id);
        }

        @Override
        public List<SignatureImpressionUseEntity> findBySignatureImageIdOrderByUsedAtDesc(
                String signatureImageId) {
            List<SignatureImpressionUseEntity> found =
                    filter(u -> u.signatureImageId().equals(signatureImageId));
            Collections.sort(found,
                    Comparator.comparing(SignatureImpressionUseEntity::usedAt).reversed());
            return found;
        }

        @Override
        public List<SignatureImpressionUseEntity> findByDocumentIdAndVersionNo(String documentId,
                Integer versionNo) {
            return filter(u -> documentId.equals(u.documentId())
                    && versionNo.equals(u.versionNo()));
        }
    }

    public static final class Renders extends InMemoryRepository<DocumentRenderEntity, String>
            implements DocumentRenderRepository {

        public Renders() {
            super(DocumentRenderEntity::id);
        }

        @Override
        public Optional<DocumentRenderEntity>
                findByDocumentIdAndVersionNoAndFormatAndConfigFingerprint(String documentId,
                        Integer versionNo, RenderFormat format, String configFingerprint) {
            return first(r -> r.documentId().equals(documentId) && r.versionNo().equals(versionNo)
                    && r.format() == format && r.configFingerprint().equals(configFingerprint));
        }

        @Override
        public List<DocumentRenderEntity> findByDocumentIdAndVersionNo(String documentId,
                Integer versionNo) {
            return filter(r -> r.documentId().equals(documentId) && r.versionNo().equals(versionNo));
        }

        @Override
        public Optional<DocumentRenderEntity> findByOutputBlobSha256(String outputBlobSha256) {
            return first(r -> r.outputBlobSha256().equals(outputBlobSha256));
        }

        @Override
        public long countByFontSetContaining(String familyFragment) {
            return filter(r -> r.fontSet().contains(familyFragment)).size();
        }
    }

    public static final class ApprovalSnapshots
            extends InMemoryRepository<ApprovalSnapshotEntity, String>
            implements ApprovalSnapshotRepository {

        public ApprovalSnapshots() {
            super(ApprovalSnapshotEntity::approvalActionId);
        }

        @Override
        public Optional<ApprovalSnapshotEntity> findByApprovalActionId(String approvalActionId) {
            return first(s -> s.approvalActionId().equals(approvalActionId));
        }

        @Override
        public List<ApprovalSnapshotEntity> findByDocumentIdAndVersionNoOrderByCreatedAtAsc(
                String documentId, Integer versionNo) {
            List<ApprovalSnapshotEntity> found = filter(s -> s.documentId().equals(documentId)
                    && s.versionNo().equals(versionNo));
            Collections.sort(found, Comparator.comparing(ApprovalSnapshotEntity::createdAt));
            return found;
        }

        @Override
        public List<ApprovalSnapshotEntity> findByDocumentIdOrderByCreatedAtAsc(String documentId) {
            List<ApprovalSnapshotEntity> found = filter(s -> s.documentId().equals(documentId));
            Collections.sort(found, Comparator.comparing(ApprovalSnapshotEntity::createdAt));
            return found;
        }

        @Override
        public List<ApprovalSnapshotEntity> findByPdfBlobSha256IsNull() {
            return filter(ApprovalSnapshotEntity::isMissingRenderedPdf);
        }
    }

    public static final class ConversionJobs
            extends InMemoryRepository<ConversionJobEntity, String>
            implements ConversionJobRepository {

        public ConversionJobs() {
            super(ConversionJobEntity::id);
        }

        @Override
        public Optional<ConversionJobEntity> findByIdempotencyKey(String idempotencyKey) {
            return first(j -> j.idempotencyKey().equals(idempotencyKey));
        }

        @Override
        public List<ConversionJobEntity> findClaimable(OffsetDateTime now, Pageable pageable) {
            List<ConversionJobEntity> found = filter(j -> j.isClaimableAt(now));
            Collections.sort(found, Comparator.comparing(ConversionJobEntity::createdAt));
            int limit = Math.min(pageable.getPageSize(), found.size());
            return new ArrayList<ConversionJobEntity>(found.subList(0, limit));
        }

        @Override
        public List<ConversionJobEntity> findByStateOrderByCreatedAtAsc(
                ConversionJobEntity.State state) {
            List<ConversionJobEntity> found = filter(j -> j.state() == state);
            Collections.sort(found, Comparator.comparing(ConversionJobEntity::createdAt));
            return found;
        }

        @Override
        public List<ConversionJobEntity> findByDocumentIdAndVersionNo(String documentId,
                Integer versionNo) {
            return filter(j -> j.documentId().equals(documentId) && j.versionNo().equals(versionNo));
        }
    }
}
