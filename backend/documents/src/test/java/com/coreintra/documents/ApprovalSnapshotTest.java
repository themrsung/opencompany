package com.coreintra.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.coreintra.documents.blob.LocalFileBlobStore;
import com.coreintra.documents.entity.ApprovalSnapshotEntity;
import com.coreintra.documents.entity.DocumentRenderEntity;
import com.coreintra.documents.entity.RenderFormat;
import com.coreintra.documents.render.RenderMetadata;
import com.coreintra.documents.service.ApprovalSnapshotService;
import com.coreintra.documents.service.BlobService;
import com.coreintra.documents.service.RenderService;
import com.coreintra.documents.support.Fakes;
import com.coreintra.documents.support.Fixture;
import com.coreintra.documents.support.InMemoryBlobStore;

/**
 * What was approved, frozen at the moment of the decision.
 *
 * <p>A document as approved must be reproducible forever, independent of any later template
 * edit, font change or reorganisation. None of those are properties of the document - they
 * all move - so the guarantee has to come from a snapshot rather than from a lookup.
 */
class ApprovalSnapshotTest {

    private static final String ACTION = "approval-action-1";
    private static final String DOCUMENT = "doc-1";
    private static final String BLOCK_STATE =
            "1|과장|김민준|APPROVE|2026-08-30T26:01:00.000|seal\n"
            + "2|부장|이서연|PENDING||";

    private Fakes.ApprovalSnapshots snapshotRows;
    private ApprovalSnapshotService snapshots;
    private RenderService renders;

    @BeforeEach
    void setUp() {
        snapshotRows = new Fakes.ApprovalSnapshots();
        BlobService blobs = new BlobService(new InMemoryBlobStore(), new Fakes.Blobs());
        snapshots = new ApprovalSnapshotService(snapshotRows, blobs);
        renders = new RenderService(new Fakes.Renders(), blobs);
    }

    private DocumentRenderEntity pdf() {
        return renders.archive("company-1", DOCUMENT, 1, RenderFormat.PDF,
                RenderMetadata.builder().rendererVersion("LibreOffice 24.2.7.2").build(),
                "%PDF-1.7 approved".getBytes(StandardCharsets.UTF_8), "application/pdf");
    }

    @Test
    @DisplayName("a snapshot records the docx, the PDF, the 결재란 and a hash of each")
    void aSnapshotRecordsAllFour() {
        byte[] docx = Fixture.leaveRequestDocx();
        DocumentRenderEntity rendered = pdf();

        ApprovalSnapshotEntity snapshot = snapshots.snapshot(ACTION, DOCUMENT, 1, docx,
                BLOCK_STATE, rendered, "signature-1");

        assertThat(snapshot.docxSha256()).isEqualTo(LocalFileBlobStore.sha256Hex(docx));
        assertThat(snapshot.pdfSha256()).isEqualTo(rendered.outputSha256());
        assertThat(snapshot.approvalBlockState()).isEqualTo(BLOCK_STATE);
        assertThat(snapshot.approvalBlockSha256()).isEqualTo(LocalFileBlobStore.sha256Hex(
                BLOCK_STATE.getBytes(StandardCharsets.UTF_8)));
        assertThat(snapshot.signatureImageId())
                .describedAs("names the enrolment, so a revoked seal is still identifiable on a "
                        + "document that legitimately carried it")
                .isEqualTo("signature-1");
    }

    @Test
    @DisplayName("snapshotting one approval action twice keeps the first account of it")
    void oneStepOneSnapshot() {
        ApprovalSnapshotEntity first =
                snapshots.snapshot(ACTION, DOCUMENT, 1, Fixture.leaveRequestDocx(), BLOCK_STATE,
                        pdf(), null);

        ApprovalSnapshotEntity second =
                snapshots.snapshot(ACTION, DOCUMENT, 1, "different".getBytes(StandardCharsets.UTF_8),
                        "different", null, null);

        assertThat(second.docxSha256()).isEqualTo(first.docxSha256());
        assertThat(snapshotRows.count())
                .describedAs("two rows would mean a later reader choosing between two accounts "
                        + "of the same decision")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an approval taken while the worker was down records the gap rather than hiding it")
    void aMissingPdfIsRecordedAsMissing() {
        ApprovalSnapshotEntity snapshot = snapshots.snapshot(ACTION, DOCUMENT, 1,
                Fixture.leaveRequestDocx(), BLOCK_STATE, null, null);

        assertThat(snapshot.isMissingRenderedPdf()).isTrue();
        assertThat(snapshot.docxBlobSha256())
                .describedAs("the docx is authoritative either way")
                .isNotNull();
        assertThat(snapshots.missingRenderedPdf())
                .describedAs("an operations queue, not a silent hole")
                .hasSize(1);
    }

    @Test
    @DisplayName("the stored hash and the stored bytes are checked against each other")
    void intactnessIsCheckable() {
        ApprovalSnapshotEntity snapshot = snapshots.snapshot(ACTION, DOCUMENT, 1,
                Fixture.leaveRequestDocx(), BLOCK_STATE, pdf(), null);

        assertThat(snapshots.isIntact(snapshot))
                .describedAs("the reference says where the bytes are; the hash says what this "
                        + "row asserts they were, and the two must agree")
                .isTrue();
    }

    @Test
    @DisplayName("a snapshot with no document is refused")
    void anEmptySnapshotProvesNothing() {
        assertThatThrownBy(() -> snapshots.snapshot(ACTION, DOCUMENT, 1, new byte[0], BLOCK_STATE,
                null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("without saying what");
    }

    @Test
    @DisplayName("a snapshot with no approval action is refused")
    void aSnapshotBelongsToAnAction() {
        assertThatThrownBy(() -> snapshots.snapshot("  ", DOCUMENT, 1, Fixture.leaveRequestDocx(),
                BLOCK_STATE, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("approval action");
    }

    @Test
    @DisplayName("the trail for a version comes back oldest first")
    void theTrailIsOrdered() {
        snapshots.snapshot("action-1", DOCUMENT, 1, Fixture.leaveRequestDocx(), BLOCK_STATE, null,
                null);
        snapshots.snapshot("action-2", DOCUMENT, 1, Fixture.leaveRequestDocx(), BLOCK_STATE, null,
                null);

        assertThat(snapshots.trailOf(DOCUMENT, 1))
                .extracting(ApprovalSnapshotEntity::approvalActionId)
                .containsExactly("action-1", "action-2");
    }
}
