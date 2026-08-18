package com.coreintra.core.service;

import com.coreintra.compat.Immutables;
import com.coreintra.compat.Texts;
import com.coreintra.core.org.Company;
import com.coreintra.core.permission.PermissionEvaluator;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionTarget;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The legal entities of the installation.
 *
 * <p>Multi-entity from day one: 본사, its 지사, and separately incorporated
 * 자회사 are all rows in the same table, distinguished by {@code kind} and by
 * who owns them. Nothing here assumes a single company, because retro-fitting
 * that assumption out later means revisiting every scoped read in the system.
 *
 * <h2>What this service deliberately does not own</h2>
 *
 * <p>The 대표이사 representation mode is a company-level setting with effective
 * dating, and it lives in {@code company_representation}. That table and its
 * domain type {@code RepresentationMode} belong to the approval module: the mode
 * exists to govern approval routing, and every rule that reads it is a rule
 * about approvals. Reaching into it from here would either duplicate the type or
 * make core-domain depend on approval, and ArchUnit fails the build for the
 * second. Setting the mode is therefore an approval-module service; this one
 * owns the row the mode hangs off.
 */
@Service
public class CompanyService {

    private final CompanyCatalogRepository companies;
    private final PermissionEvaluator evaluator;

    public CompanyService(CompanyCatalogRepository companies, PermissionEvaluator evaluator) {
        if (companies == null) {
            throw new NullPointerException("companies");
        }
        if (evaluator == null) {
            throw new NullPointerException("evaluator");
        }
        this.companies = companies;
        this.evaluator = evaluator;
    }

    /**
     * The companies this caller may see, in code order.
     *
     * <p>A denial filters rather than throws. A list is a view of what you are
     * allowed to see, and a 지사 manager asking for the company list is not making
     * a mistake by not being able to see the rest of the group; whereas asking
     * for one company by id and being refused is an answer they need to be told.
     */
    @Transactional(readOnly = true)
    public List<Company> list(PermissionPrincipal caller, LocalDate businessDate) {
        Arguments.caller(caller, businessDate);
        List<Company> visible = new ArrayList<Company>();
        for (Company company : companies.findAllByOrderByCodeAsc()) {
            if (evaluator.check(caller, OrgPermissions.COMPANY_READ, target(company, businessDate)).isAllowed()) {
                visible.add(company);
            }
        }
        return Immutables.copyOf(visible);
    }

    @Transactional(readOnly = true)
    public Company read(PermissionPrincipal caller, String companyId, LocalDate businessDate) {
        Arguments.caller(caller, businessDate);
        Company company = require(companyId);
        evaluator.check(caller, OrgPermissions.COMPANY_READ, target(company, businessDate)).orThrow();
        return company;
    }

    /**
     * Adds a legal entity.
     *
     * <p>Checked installation-wide, not against the parent: incorporating a
     * subsidiary is not something authority over 본사 should imply, and the new
     * company has no id to scope a grant to until it exists.
     */
    @Transactional
    public Company create(PermissionPrincipal caller, String code, String nameKo, Company.CompanyKind kind,
            String parentCompanyId, LocalDate businessDate) {
        Arguments.caller(caller, businessDate);
        evaluator.check(caller, OrgPermissions.COMPANY_CREATE,
                PermissionTarget.installationWide(businessDate)).orThrow();

        String cleanCode = Arguments.required(code, "code");
        String cleanName = Arguments.required(nameKo, "nameKo");
        if (kind == null) {
            throw new IllegalArgumentException("kind is required");
        }
        if (companies.findByCode(cleanCode).isPresent()) {
            throw new IllegalArgumentException("company code is already in use: " + cleanCode);
        }
        String parent = requireOwnership(kind, parentCompanyId);

        Company company = new Company(UUID.randomUUID().toString(), cleanCode, cleanName, kind);
        if (parent != null) {
            company.setParentCompanyId(parent);
        }
        return companies.save(company);
    }

    @Transactional
    public Company rename(PermissionPrincipal caller, String companyId, String nameKo, String nameEn,
            LocalDate businessDate) {
        Company company = forUpdate(caller, companyId, businessDate);
        company.rename(Arguments.required(nameKo, "nameKo"), Arguments.optional(nameEn));
        return companies.save(company);
    }

    /**
     * The registration facts.
     *
     * <p>{@code baseCurrencyCode} matters only when accounting is switched on,
     * but it is set here rather than there: the ledger reads the company row, and
     * a currency that appears the moment the accounting module is installed is a
     * currency nobody decided on.
     */
    @Transactional
    public Company updateRegistration(PermissionPrincipal caller, String companyId,
            String businessRegistrationNumber, String baseCurrencyCode, LocalDate establishedOn,
            LocalDate businessDate) {
        Company company = forUpdate(caller, companyId, businessDate);
        company.setBusinessRegistrationNumber(Arguments.optional(businessRegistrationNumber));
        company.setBaseCurrencyCode(currency(baseCurrencyCode));
        company.setEstablishedOn(establishedOn);
        return companies.save(company);
    }

    /**
     * Moves a company under a different owner.
     *
     * <p>Both ends are checked. Re-parenting is the group-level twin of moving an
     * org unit: it changes which company a COMPANY-scoped grant on the parent
     * effectively presides over, so authority over the company being moved is not
     * enough on its own.
     */
    @Transactional
    public Company reparent(PermissionPrincipal caller, String companyId, String newParentCompanyId,
            LocalDate businessDate) {
        Arguments.caller(caller, businessDate);
        Company company = require(companyId);
        evaluator.check(caller, OrgPermissions.COMPANY_UPDATE, target(company, businessDate)).orThrow();

        String parentId = requireOwnership(company.kind(), newParentCompanyId);
        if (parentId != null) {
            Company parent = require(parentId);
            evaluator.check(caller, OrgPermissions.COMPANY_UPDATE, target(parent, businessDate)).orThrow();
            refuseOwnershipCycle(company, parent);
        }
        company.setParentCompanyId(parentId);
        return companies.save(company);
    }

    /**
     * Takes a company out of use.
     *
     * <p>Never a delete. Journal entries, approvals and employment records point
     * at this row for as long as they are kept, and an installation that can lose
     * the name of the company an approval was signed in cannot answer an audit.
     */
    @Transactional
    public Company deactivate(PermissionPrincipal caller, String companyId, LocalDate businessDate) {
        Company company = forUpdate(caller, companyId, businessDate);
        if (!companies.findByParentCompanyId(company.id()).isEmpty()) {
            throw new IllegalArgumentException(
                    "company " + company.code() + " still owns other companies; deactivate or re-parent them first");
        }
        company.deactivate();
        return companies.save(company);
    }

    private Company forUpdate(PermissionPrincipal caller, String companyId, LocalDate businessDate) {
        Arguments.caller(caller, businessDate);
        Company company = require(companyId);
        evaluator.check(caller, OrgPermissions.COMPANY_UPDATE, target(company, businessDate)).orThrow();
        return company;
    }

    private Company require(String companyId) {
        Optional<Company> found = companies.findById(Arguments.required(companyId, "companyId"));
        if (!found.isPresent()) {
            throw RecordNotFoundException.of("company", companyId);
        }
        return found.get();
    }

    /**
     * Mirrors {@code company_parent_matches_kind}: 본사 owns nobody above it, and
     * a 지사 or 자회사 must say who it belongs to.
     *
     * <p>Checked here as well as in the database because the constraint speaks in
     * SQL and the person who typed the form needs a sentence.
     */
    private String requireOwnership(Company.CompanyKind kind, String parentCompanyId) {
        String parent = Texts.isBlank(parentCompanyId) ? null : Texts.strip(parentCompanyId);
        if (kind == Company.CompanyKind.HEAD_OFFICE) {
            if (parent != null) {
                throw new IllegalArgumentException("본사 cannot belong to another company");
            }
            return null;
        }
        if (parent == null) {
            throw new IllegalArgumentException("a " + kind + " must say which company it belongs to");
        }
        return parent;
    }

    /**
     * Walks the ownership chain upwards from the proposed parent.
     *
     * <p>The database will not catch this: a foreign key onto the same table is
     * satisfied by a cycle. A group that owns itself has no head office, and every
     * report that walks ownership hangs.
     */
    private void refuseOwnershipCycle(Company company, Company proposedParent) {
        Set<String> seen = new HashSet<String>();
        Company cursor = proposedParent;
        while (cursor != null) {
            if (cursor.id().equals(company.id())) {
                throw new IllegalArgumentException("company " + company.code()
                        + " cannot belong to " + proposedParent.code() + ": it already owns it");
            }
            if (!seen.add(cursor.id())) {
                return;
            }
            String next = cursor.parentCompanyId();
            cursor = next == null ? null : companies.findById(next).orElse(null);
        }
    }

    private static String currency(String value) {
        if (Texts.isBlank(value)) {
            return null;
        }
        String code = Texts.strip(value).toUpperCase();
        if (code.length() > 12) {
            throw new IllegalArgumentException("currency code is too long: " + code);
        }
        return code;
    }

    private static PermissionTarget target(Company company, LocalDate businessDate) {
        return OrgTargets.company(company.id(), businessDate, "company " + company.code());
    }

}
