package com.coreintra.documents.signature;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Texts;
import com.coreintra.documents.blob.BlobRef;
import com.coreintra.documents.entity.SignatureImageEntity;
import com.coreintra.documents.entity.SignatureImpressionUseEntity;
import com.coreintra.documents.repository.SignatureImageRepository;
import com.coreintra.documents.repository.SignatureImpressionUseRepository;
import com.coreintra.documents.service.BlobService;

/**
 * Enrolling, revoking and using 도장 / 서명 images.
 *
 * <h2>The bytes have exactly one exit</h2>
 *
 * <p>Brief 6.3: signature images are per-employee, enrolled and audited, revocable, and
 * composited server-side at render only - never served to the browser as a reusable asset.
 * Every method here that could return content returns a {@link SignatureImpression}
 * instead, which has no public accessor for its bytes. The only code that can read them is
 * {@link SignatureCompositor}, in this package, and it writes them into a render workspace
 * rather than returning them.
 *
 * <p>The point is not that a controller is forbidden from serving a seal. It is that a
 * controller <em>cannot</em>: there is no expression it could write that yields the bytes.
 *
 * <h2>Every impression is recorded</h2>
 *
 * <p>{@link #impressionFor} writes a row before it hands anything back, so "was my seal
 * used on this?" is a query rather than a shrug. That includes impressions that go on to
 * fail: the seal left the store either way.
 */
@Service
public class SignatureService {

    private final SignatureImageRepository signatures;

    private final SignatureImpressionUseRepository uses;

    private final BlobService blobs;

    public SignatureService(SignatureImageRepository signatures,
            SignatureImpressionUseRepository uses, BlobService blobs) {
        this.signatures = signatures;
        this.uses = uses;
        this.blobs = blobs;
    }

    /**
     * Enrols a seal for an employee, replacing any live one of the same kind.
     *
     * <p>The previous seal is revoked rather than overwritten: a document rendered last
     * year carried the old one, and the trail has to be able to say so. Two live seals of
     * one kind cannot exist - the database refuses them - because a renderer choosing
     * between them leaves nobody able to say afterwards which it chose.
     */
    @Transactional
    public SignatureImageEntity enrol(String companyId, String employeeId,
            SignatureImageEntity.Kind kind, byte[] image, String contentType,
            String enrolledByAccountId, BusinessInstant enrolledAt) {
        if (image == null || image.length == 0) {
            throw new IllegalArgumentException(
                    "an empty seal renders as a blank 결재란, which looks signed");
        }
        if (Texts.isBlank(contentType)) {
            throw new IllegalArgumentException("a seal needs its media type; the renderer asks");
        }
        Optional<SignatureImageEntity> live =
                signatures.findByEmployeeIdAndKindAndRevokedAtIsNull(employeeId, kind);
        if (live.isPresent()) {
            live.get().revoke(enrolledByAccountId, "replaced by a newly enrolled "
                    + kind.name().toLowerCase(Locale.ROOT), OffsetDateTime.now());
            signatures.save(live.get());
        }
        BlobRef ref = blobs.store(image, contentType, "seal-" + employeeId);
        return signatures.save(new SignatureImageEntity(UUID.randomUUID().toString(), companyId,
                employeeId, kind, ref.sha256(), contentType, enrolledByAccountId, enrolledAt));
    }

    /**
     * Withdraws a seal from future renders.
     *
     * <p>Not a delete. Documents already rendered with it stay valid and stay explicable,
     * and the reason is required because a revocation with no reason is a mystery in an
     * audit six months later.
     */
    @Transactional
    public SignatureImageEntity revoke(String signatureImageId, String byAccountId, String reason,
            OffsetDateTime at) {
        SignatureImageEntity signature = signatures.findById(signatureImageId).orElseThrow(
                () -> new IllegalArgumentException("no such signature image: " + signatureImageId));
        signature.revoke(byAccountId, reason, at);
        return signatures.save(signature);
    }

    /** The live seal for an employee, as metadata. Never content: that is what impressions are for. */
    @Transactional(readOnly = true)
    public Optional<SignatureImageEntity> liveSealOf(String employeeId,
            SignatureImageEntity.Kind kind) {
        return signatures.findByEmployeeIdAndKindAndRevokedAtIsNull(employeeId, kind);
    }

    @Transactional(readOnly = true)
    public List<SignatureImageEntity> liveSealsOf(String companyId) {
        return signatures.findByCompanyIdAndRevokedAtIsNull(companyId);
    }

    /**
     * Fetches a seal for one render, and records that it happened.
     *
     * <p>The return type is the guarantee. A {@link SignatureImpression} carries the image
     * without exposing it: the only code that can read the bytes is the compositor in this
     * package, which writes them into a render workspace. Nothing a controller can call
     * returns content, so no endpoint can serve a seal even by mistake.
     *
     * <p>A revoked seal is not returned. The revocation is checked in the same query that
     * fetches the content hash, so there is no window between "is it live?" and "give me
     * the bytes" for a revocation to slip through.
     *
     * @param purpose short, and stored: the audit row is read by the person whose seal it is
     * @return empty when the employee has no live seal of that kind - which is normal, and
     *         means the 결재란 renders the name without an impression rather than failing
     */
    @Transactional
    public Optional<SignatureImpression> impressionFor(String employeeId,
            SignatureImageEntity.Kind kind, String documentId, Integer versionNo,
            String requestedByAccountId, String purpose) {
        if (Texts.isBlank(purpose)) {
            throw new IllegalArgumentException(
                    "record what the seal is being used for; the audit row is read by the person "
                    + "whose seal it is, and \"unknown\" answers nothing");
        }
        Optional<SignatureImageEntity> live =
                signatures.findByEmployeeIdAndKindAndRevokedAtIsNull(employeeId, kind);
        if (!live.isPresent()) {
            return Optional.empty();
        }
        SignatureImageEntity signature = live.get();
        Optional<String> contentHash = signatures.liveContentHashForRenderer(signature.id());
        if (!contentHash.isPresent()) {
            return Optional.empty();
        }

        // Recorded before the bytes move, not after: a failure between the two must leave
        // evidence that the seal was fetched, not evidence that it was not.
        uses.save(new SignatureImpressionUseEntity(UUID.randomUUID().toString(), signature.id(),
                documentId, versionNo, requestedByAccountId, purpose));

        return Optional.of(new SignatureImpression(signature.id(), signature.contentType(),
                blobs.contentOf(contentHash.get())));
    }

    /** "Where has my seal been used?" - the question the use log exists to answer. */
    @Transactional(readOnly = true)
    public List<SignatureImpressionUseEntity> usesOf(String signatureImageId) {
        return uses.findBySignatureImageIdOrderByUsedAtDesc(signatureImageId);
    }
}
