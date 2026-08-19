package com.coreintra.documents.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.coreintra.documents.entity.BlobEntity;

/** The index over the blob store. */
public interface BlobRepository extends JpaRepository<BlobEntity, String> {

    /**
     * Blobs no live row points at: the reaper's whole input.
     *
     * <p>Reads the {@code blob_unreferenced} view rather than assembling the UNION here,
     * because the view is the checklist a new blob column has to be added to and a query
     * hidden in Java is a column everyone forgets.
     */
    @Query(value = "SELECT * FROM blob_unreferenced", nativeQuery = true)
    List<BlobEntity> findUnreferenced();

    /** Everything still on the volume. A reaped row is a tombstone, not a candidate. */
    List<BlobEntity> findByBytesReapedAtIsNull();

    /**
     * Whether anything still points at these bytes.
     *
     * <p>Asked immediately before a reap, against the same view the candidate list came
     * from. The two questions are separated by however long the reaper takes to work
     * through its list, and an approved document arriving in that window must not lose
     * its bytes.
     */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM blob_reference r WHERE r.sha256 = :sha256)",
            nativeQuery = true)
    boolean isReferenced(@Param("sha256") String sha256);
}
