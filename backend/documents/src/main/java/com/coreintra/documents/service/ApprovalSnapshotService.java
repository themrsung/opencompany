package com.coreintra.documents.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coreintra.compat.Texts;
import com.coreintra.documents.blob.BlobRef;
import com.coreintra.documents.blob.LocalFileBlobStore;
import com.coreintra.documents.entity.ApprovalSnapshotEntity;
import com.coreintra.documents.entity.DocumentRenderEntity;
import com.coreintra.documents.repository.ApprovalSnapshotRepository;

/**
 * Freezes what was approved, at every approval step (brief 6.3).
 *
 * <p>A document as approved must be reproducible forever, independent of any later template
 * edit, font change or reorganisation. That is not a property of the document - the
 * template moves, the org chart moves, the fonts move - so it has to be a property of a
 * snapshot taken at the moment of the decision.
 *
 * <p>Idempotent on the approval action: taking the same snapshot twice returns the first
 * one. An approval step happens once, and a second row would mean a later reader choosing
 * between two accounts of the same decision.
 */
@Service
public class ApprovalSnapshotService {

    private final ApprovalSnapshotRepository snapshots;

    private final BlobService blobs;

    public ApprovalSnapshotService(ApprovalSnapshotRepository snapshots, BlobService blobs) {
        this.snapshots = snapshots;
        this.blobs = blobs;
    }

    /**
     * Records the document, the approval-block state and their hashes.
     *
     * @param approvalBlockState the resolved 결재란 as rendered - 직급, name, action, business
     *        date, and whether a 도장 was composited. Stored as text rather than rebuilt on
     *        demand: a 과장 who is a 부장 by the time anyone reads the trail signed as a 과장.
     * @param renderedPdf may be null when the conversion worker was unavailable. The docx is
     *        authoritative either way, and recording the gap is honest where back-filling a
     *        PDF later - against a font set that may since have changed - would not be.
     */
    @Transactional
    public ApprovalSnapshotEntity snapshot(String approvalActionId, String documentId, int versionNo,
            byte[] docx, String approvalBlockState, DocumentRenderEntity renderedPdf,
            String signatureImageId) {
        if (Texts.isBlank(approvalActionId)) {
            throw new IllegalArgumentException(
                    "a snapshot belongs to an approval action; without one it is evidence of "
                    + "nothing in particular");
        }
        if (docx == null || docx.length == 0) {
            throw new IllegalArgumentException(
                    "an approval snapshot with no document proves that something was approved "
                    + "without saying what, which is the failure this table exists to prevent");
        }
        if (approvalBlockState == null) {
            throw new IllegalArgumentException(
                    "record the 결재란 as it was rendered, even if it was empty at this step");
        }

        Optional<ApprovalSnapshotEntity> existing =
                snapshots.findByApprovalActionId(approvalActionId);
        if (existing.isPresent()) {
            return existing.get();
        }

        BlobRef stored = blobs.store(docx,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                documentId + "-v" + versionNo + ".docx");
        ApprovalSnapshotEntity snapshot = new ApprovalSnapshotEntity(approvalActionId, documentId,
                versionNo, stored.sha256(), LocalFileBlobStore.sha256Hex(docx), approvalBlockState,
                LocalFileBlobStore.sha256Hex(approvalBlockState.getBytes(StandardCharsets.UTF_8)));
        if (renderedPdf != null) {
            snapshot.withRenderedPdf(renderedPdf.outputBlobSha256(), renderedPdf.outputSha256(),
                    renderedPdf.id());
        }
        if (signatureImageId != null) {
            snapshot.withSignature(signatureImageId);
        }
        return snapshots.save(snapshot);
    }

    @Transactional(readOnly = true)
    public Optional<ApprovalSnapshotEntity> forAction(String approvalActionId) {
        return snapshots.findByApprovalActionId(approvalActionId);
    }

    /** The whole trail for one version, oldest first. */
    @Transactional(readOnly = true)
    public List<ApprovalSnapshotEntity> trailOf(String documentId, int versionNo) {
        return snapshots.findByDocumentIdAndVersionNoOrderByCreatedAtAsc(documentId,
                Integer.valueOf(versionNo));
    }

    /**
     * Checks a snapshot against the bytes still in the store.
     *
     * <p>Returns true when they agree. A false is not a cache miss: it means the reference
     * and the assertion this row made about it have diverged, and something has rewritten
     * history. That is the finding, and the reason both columns exist.
     */
    @Transactional(readOnly = true)
    public boolean isIntact(ApprovalSnapshotEntity snapshot) {
        byte[] stored = blobs.contentOf(snapshot.docxBlobSha256());
        return snapshot.docxSha256().equals(LocalFileBlobStore.sha256Hex(stored));
    }

    /** Approvals recorded while the conversion worker was down. An operations queue. */
    @Transactional(readOnly = true)
    public List<ApprovalSnapshotEntity> missingRenderedPdf() {
        return snapshots.findByPdfBlobSha256IsNull();
    }
}
