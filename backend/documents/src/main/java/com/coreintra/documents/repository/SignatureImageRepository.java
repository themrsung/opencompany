package com.coreintra.documents.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.coreintra.documents.entity.SignatureImageEntity;

/**
 * Enrolled seals and signatures.
 *
 * <p>Note what is missing: {@link SignatureImageEntity} has no public accessor for its
 * content hash, so nothing outside this one query can find its way to the bytes. The
 * projection below reads the field through JPQL and is used by exactly one caller, the
 * signature compositor. It returns a hash, never content, and the content itself is
 * reachable only inside {@code com.coreintra.documents.signature}, where it goes into a
 * render workspace rather than into a response.
 */
public interface SignatureImageRepository extends JpaRepository<SignatureImageEntity, String> {

    /** The live seal for one employee. Two live seals of one kind cannot exist (see V7). */
    Optional<SignatureImageEntity> findByEmployeeIdAndKindAndRevokedAtIsNull(
            String employeeId, SignatureImageEntity.Kind kind);

    List<SignatureImageEntity> findByCompanyIdAndRevokedAtIsNull(String companyId);

    /**
     * The content hash of a live seal, for the compositor alone.
     *
     * @return empty when the seal does not exist or has been revoked - a revoked seal
     *         must not reach a render, and returning empty is how that is enforced at
     *         the only door that opens
     */
    @Query("select s.blobSha256 from SignatureImageEntity s "
            + "where s.id = :signatureImageId and s.revokedAt is null")
    Optional<String> liveContentHashForRenderer(@Param("signatureImageId") String signatureImageId);
}
