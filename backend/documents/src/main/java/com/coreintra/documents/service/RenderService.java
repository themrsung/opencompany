package com.coreintra.documents.service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coreintra.documents.blob.BlobRef;
import com.coreintra.documents.blob.LocalFileBlobStore;
import com.coreintra.documents.entity.DocumentRenderEntity;
import com.coreintra.documents.entity.RenderFormat;
import com.coreintra.documents.render.RenderMetadata;
import com.coreintra.documents.repository.DocumentRenderRepository;

/**
 * Archives renders, and prefers the archive to the renderer.
 *
 * <h2>The archived render is authoritative</h2>
 *
 * <p>A 결재 document has to be reproducible years later. The LibreOffice path cannot be
 * made byte-deterministic - the best available is to pin the version and record the inputs
 * - so if a regenerated PDF ever fails to match the archived one, <b>the archived one is
 * what was approved</b> (brief 6.4). This service is built around that: a lookup by
 * configuration returns the archived bytes rather than re-rendering, and when a re-render
 * does happen and differs, {@link #explainDifference} says what changed instead of shrugging.
 */
@Service
public class RenderService {

    private final DocumentRenderRepository renders;

    private final BlobService blobs;

    public RenderService(DocumentRenderRepository renders, BlobService blobs) {
        this.renders = renders;
        this.blobs = blobs;
    }

    /** The configuration's identity. Same fingerprint, same bytes expected. */
    public String fingerprintOf(RenderFormat format, RenderMetadata metadata) {
        return RenderFingerprint.of(format, metadata);
    }

    /**
     * The archived render for this exact configuration, if there is one.
     *
     * <p>Ask this before rendering. A hit is not a cache optimisation - it is the answer,
     * because re-rendering the same inputs can only differ, and a difference here is a
     * problem rather than a refresh.
     */
    @Transactional(readOnly = true)
    public Optional<DocumentRenderEntity> findArchived(String documentId, int versionNo,
            RenderFormat format, RenderMetadata metadata) {
        return findArchived(documentId, versionNo, format, fingerprintOf(format, metadata));
    }

    @Transactional(readOnly = true)
    public Optional<DocumentRenderEntity> findArchived(String documentId, int versionNo,
            RenderFormat format, String configFingerprint) {
        return renders.findByDocumentIdAndVersionNoAndFormatAndConfigFingerprint(
                documentId, Integer.valueOf(versionNo), format, configFingerprint);
    }

    /**
     * Records a render and its output.
     *
     * <p>Idempotent on the configuration: archiving the same render twice returns the first
     * row rather than creating a second, so a retried conversion job cannot produce two
     * archives of the same thing and leave a later reader choosing between them.
     *
     * @throws IllegalArgumentException if the metadata's output hash disagrees with the
     *         bytes handed in. That disagreement means the metadata describes a different
     *         artefact than the one being stored, and every later comparison would be
     *         against the wrong record.
     */
    @Transactional
    public DocumentRenderEntity archive(String companyId, String documentId, int versionNo,
            RenderFormat format, RenderMetadata metadata, byte[] output, String contentType) {
        String actualHash = LocalFileBlobStore.sha256Hex(output);
        if (metadata.outputSha256() != null && !metadata.outputSha256().equals(actualHash)) {
            throw new IllegalArgumentException(
                    "render metadata claims output " + metadata.outputSha256() + " but the bytes "
                    + "hash to " + actualHash + ". One of them describes a different artefact.");
        }

        String fingerprint = fingerprintOf(format, metadata);
        Optional<DocumentRenderEntity> existing =
                findArchived(documentId, versionNo, format, fingerprint);
        if (existing.isPresent()) {
            return existing.get();
        }

        BlobRef ref = blobs.store(output, contentType, documentId + "-v" + versionNo + "."
                + format.name().toLowerCase(Locale.ROOT));
        DocumentRenderEntity render = new DocumentRenderEntity(UUID.randomUUID().toString(),
                companyId, documentId, versionNo, format, fingerprint, ref.sha256(),
                metadata.rendererVersion(), actualHash,
                RenderMetadataCodec.renderedAtOrNow(metadata));
        render.describeInputs(metadata.templateId(),
                metadata.templateId() == null ? null : Integer.valueOf(metadata.templateVersion()),
                metadata.locale(),
                RenderMetadataCodec.encodeFontSet(metadata.fontSet()),
                RenderMetadataCodec.encodeSubstitutions(metadata.substitutions()),
                RenderMetadataCodec.encodeExtra(metadata.extra()),
                metadata.documentSha256());
        return renders.save(render);
    }

    /** The archived bytes. What a download of an approved document actually serves. */
    @Transactional(readOnly = true)
    public byte[] outputOf(DocumentRenderEntity render) {
        return blobs.contentOf(render.outputBlobSha256());
    }

    @Transactional(readOnly = true)
    public List<DocumentRenderEntity> rendersOf(String documentId, int versionNo) {
        return renders.findByDocumentIdAndVersionNo(documentId, Integer.valueOf(versionNo));
    }

    /**
     * Why a regenerated render differs from the archived one, most likely cause first.
     *
     * <p>An empty list is the uncomfortable answer: the inputs were identical and the bytes
     * are not, which means the renderer is not as deterministic as its version number
     * claims. Either way the archived render stands - this explains the difference rather
     * than excusing it.
     */
    @Transactional(readOnly = true)
    public List<String> explainDifference(DocumentRenderEntity archived, RenderMetadata regenerated) {
        return RenderMetadataCodec.toMetadata(archived).explainDifferenceFrom(regenerated);
    }
}
