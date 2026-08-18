package com.coreintra.documents.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.coreintra.documents.entity.FontEntity;

/** The one font store the worker, the browser editor and mdv all read from. */
public interface FontRepository extends JpaRepository<FontEntity, String> {

    /**
     * What is installed for one company: its own uploads plus everything shipped.
     *
     * <p>A NULL {@code companyId} is the bundled set, which is why this is a written query
     * rather than a derived one - {@code findByCompanyId(null)} would return the bundled
     * fonts and nothing else, silently, and the client's own uploads would vanish from
     * the manager UI.
     */
    @Query("select f from FontEntity f "
            + "where f.retiredAt is null "
            + "and (f.companyId is null or f.companyId = :companyId) "
            + "order by f.family asc, f.style asc")
    List<FontEntity> findInstalledFor(@Param("companyId") String companyId);

    Optional<FontEntity> findByCompanyIdAndFamilyAndStyle(String companyId, String family, String style);

    /** Every row that references these bytes: the count shown before a font is removed. */
    List<FontEntity> findByBlobSha256AndRetiredAtIsNull(String blobSha256);
}
