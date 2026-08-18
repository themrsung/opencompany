package com.coreintra.app.install;

import com.coreintra.compat.Texts;
import com.coreintra.core.permission.PermissionEvaluator;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionTarget;
import com.coreintra.core.service.OrgPermissions;
import java.time.LocalDate;
import javax.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gives a company the factory catalogue: 직급 ladder, 직무 set, the six
 * attendance statuses, and the Korean 연차 policy with its tenure ladder.
 *
 * <p>{@code V6__seed_defaults.sql} wrote exactly these rows, once, for the
 * companies that existed the day it ran — on a fresh install, none. Every
 * company created afterwards started with an empty ladder and no attendance
 * statuses, so a client adding a second company hit the same unusable box the
 * first one did.
 *
 * <h2>Where the values live</h2>
 *
 * <p>Not here. {@code install_company_defaults()} in
 * {@code V15__installation.sql} holds them, and that file is the source of
 * truth; this class calls it. A second copy of the Korean labels in Java would
 * drift from the migration's within a release, and the drift would show up as
 * two companies in the same installation disagreeing about what 재택 is called.
 *
 * <h2>Why it is asked for rather than automatic</h2>
 *
 * <p>There is no trigger on {@code company} that applies this. Companies are
 * created through {@code CompanyService}, and the catalogue services refuse a
 * duplicate code by design — so a company that silently arrived with a full
 * ladder would make {@code RankService.create("SAWON", …)} fail for the next
 * caller who meant to lay their own. Applying the defaults is therefore a
 * decision the caller takes, once, and it is idempotent: rows that exist are
 * left exactly as the client edited them.
 */
@Component
public class CompanyDefaults {

    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final PermissionEvaluator evaluator;

    public CompanyDefaults(JdbcTemplate jdbc, EntityManager entityManager,
            PermissionEvaluator evaluator) {
        this.jdbc = jdbc;
        this.entityManager = entityManager;
        this.evaluator = evaluator;
    }

    /**
     * Applies the factory catalogue to one company.
     *
     * <p>Gated on {@code company.settings:update} — this is configuration of a
     * company — and additionally on {@code hr.rank:create}, because the ladder
     * is the part of the catalogue that carries permissions and somebody who
     * could not create a rung by hand should not acquire seven of them this way.
     *
     * @return how many rows were written; zero when the company already had
     *         everything, which is a normal answer rather than a failure
     */
    @Transactional
    public int applyTo(PermissionPrincipal caller, String companyId, LocalDate businessDate) {
        if (caller == null) {
            throw new NullPointerException("caller");
        }
        if (businessDate == null) {
            throw new NullPointerException("businessDate");
        }
        if (Texts.isBlank(companyId)) {
            throw new IllegalArgumentException("companyId is blank");
        }
        String company = Texts.strip(companyId);
        PermissionTarget target = PermissionTarget.builder()
                .companyId(company)
                .asOfBusinessDate(businessDate)
                .description("applying the factory catalogue to company " + company)
                .build();
        evaluator.check(caller, OrgPermissions.COMPANY_UPDATE, target).orThrow();
        evaluator.check(caller, OrgPermissions.RANK_CREATE, target).orThrow();

        // The company may have been created moments ago in this same
        // transaction and still be sitting unflushed in the persistence
        // context: a JDBC statement does not trigger the flush that a JPQL
        // query would, and the function's first act is to check the company
        // exists. Without this the installer fails on the company it just made.
        entityManager.flush();

        Integer written = jdbc.queryForObject(
                "select install_company_defaults(?)", Integer.class, company);
        return written == null ? 0 : written.intValue();
    }
}
