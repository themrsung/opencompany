package com.coreintra.documents.entity;

import java.time.OffsetDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * What was approved, at one approval step, provably.
 *
 * <p>Brief 6.3: at every approval step, snapshot the docx bytes, the rendered PDF, the
 * approval-block state and a SHA-256 of each. Keyed by the approval action, so the trail
 * has no gaps - an action with no row here is an approval nobody can reconstruct.
 *
 * <h2>Why the hash is stored when the blob key IS the hash</h2>
 *
 * <p>The two columns answer different questions. The blob reference says where the bytes
 * are; the hash says what this row asserts they were. If a migration, a restore from an
 * older backup or a bug ever repointed the reference, the columns would disagree - and the
 * disagreement is the finding. Storing only one of them makes that class of error
 * undetectable, which for the approval trail is the whole point of having it.
 *
 * <p>Append-only. A document as approved must be reproducible forever, independent of any
 * later template edit, font change or reorganisation.
 */
@Entity
@Table(name = "approval_snapshot")
public class ApprovalSnapshotEntity {

    /** The approval action. One step, one snapshot; a duplicate is a bug, not a second opinion. */
    @Id
    @Column(name = "approval_action_id", length = 36)
    private String approvalActionId;

    @Column(name = "document_id", nullable = false, length = 36)
    private String documentId;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "docx_blob_sha256", nullable = false, length = 64)
    private String docxBlobSha256;

    @Column(name = "docx_sha256", nullable = false, length = 64)
    private String docxSha256;

    @Column(name = "pdf_blob_sha256", length = 64)
    private String pdfBlobSha256;

    @Column(name = "pdf_sha256", length = 64)
    private String pdfSha256;

    @Column(name = "pdf_render_id", length = 36)
    private String pdfRenderId;

    /**
     * The resolved 결재란 as rendered: 직급, name, action, business-time date and whether a
     * 도장 was composited, per approver.
     *
     * <p>Kept as text rather than rebuilt from the org chart on demand, because the org
     * chart moves and the document does not. A 과장 who is a 부장 by the time anyone reads
     * the trail signed as a 과장.
     */
    @Column(name = "approval_block_state", nullable = false)
    private String approvalBlockState;

    @Column(name = "approval_block_sha256", nullable = false, length = 64)
    private String approvalBlockSha256;

    /**
     * The 도장 composited into this snapshot's PDF, if any.
     *
     * <p>Names the enrolment, not the image: a revoked seal must still be identifiable on a
     * document that legitimately carried it.
     */
    @Column(name = "signature_image_id", length = 36)
    private String signatureImageId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected ApprovalSnapshotEntity() {
    }

    public ApprovalSnapshotEntity(String approvalActionId, String documentId, int versionNo,
            String docxBlobSha256, String docxSha256, String approvalBlockState,
            String approvalBlockSha256) {
        this.approvalActionId = approvalActionId;
        this.documentId = documentId;
        this.versionNo = Integer.valueOf(versionNo);
        this.docxBlobSha256 = docxBlobSha256;
        this.docxSha256 = docxSha256;
        this.approvalBlockState = approvalBlockState;
        this.approvalBlockSha256 = approvalBlockSha256;
        this.createdAt = OffsetDateTime.now();
    }

    /**
     * Attaches the rendered PDF.
     *
     * <p>Separate from construction because the conversion worker may be down at the moment
     * of approval. The docx is authoritative either way, and a snapshot without a PDF
     * records the gap rather than filling it in later with a render made against a font set
     * that may since have changed.
     */
    public void withRenderedPdf(String pdfBlobSha256, String pdfSha256, String renderId) {
        this.pdfBlobSha256 = pdfBlobSha256;
        this.pdfSha256 = pdfSha256;
        this.pdfRenderId = renderId;
    }

    public void withSignature(String signatureImageId) {
        this.signatureImageId = signatureImageId;
    }

    public String approvalActionId() {
        return approvalActionId;
    }

    public String documentId() {
        return documentId;
    }

    public Integer versionNo() {
        return versionNo;
    }

    public String docxBlobSha256() {
        return docxBlobSha256;
    }

    public String docxSha256() {
        return docxSha256;
    }

    public String pdfBlobSha256() {
        return pdfBlobSha256;
    }

    public String pdfSha256() {
        return pdfSha256;
    }

    public String pdfRenderId() {
        return pdfRenderId;
    }

    public String approvalBlockState() {
        return approvalBlockState;
    }

    public String approvalBlockSha256() {
        return approvalBlockSha256;
    }

    public String signatureImageId() {
        return signatureImageId;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    /** True when the approval was recorded without a PDF because the worker was unavailable. */
    public boolean isMissingRenderedPdf() {
        return pdfBlobSha256 == null;
    }
}
