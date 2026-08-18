package com.coreintra.approval.service;

import com.coreintra.approval.domain.RepresentationMode;
import java.time.LocalDate;

/**
 * The company's 대표 representation mode, as it stood on a business date.
 *
 * <p>A port rather than a repository call because {@code company_representation}
 * (V4) has no JPA entity yet, and entity ownership sits outside this layer. The
 * shape here matches that table exactly — mode, quorum, designated count,
 * effective dating — so an adapter is a mapping and nothing more.
 *
 * <h2>Why it is effective-dated rather than current</h2>
 *
 * <p>A document submitted in March and approved in May was routed under March's
 * mode. If the company switched from 각자대표 to 공동대표 in April, re-reading
 * "the current mode" would retroactively invalidate an approval that was
 * complete under the rules in force when it was given. Every caller here passes
 * the document's own business date for that reason.
 */
public interface RepresentationDirectory {

    /**
     * The mode in force for {@code companyId} on {@code businessDate}.
     *
     * @throws IllegalStateException if the company has no representation row
     *         covering that date — guessing 각자대표 would silently downgrade a
     *         joint-representation company to single-signature approval, which
     *         is the one failure mode this whole area exists to prevent
     */
    RepresentationMode modeOn(String companyId, LocalDate businessDate);
}
