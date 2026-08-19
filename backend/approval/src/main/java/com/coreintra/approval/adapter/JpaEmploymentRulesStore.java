package com.coreintra.approval.adapter;

import com.coreintra.approval.domain.ApprovalState;
import com.coreintra.approval.entity.EmploymentRulesAcknowledgementEntity;
import com.coreintra.approval.entity.EmploymentRulesEntity;
import com.coreintra.approval.entity.EmploymentRulesRepresentativeEntity;
import com.coreintra.approval.entity.EmploymentRulesSectionEntity;
import com.coreintra.approval.repository.EmploymentRulesAcknowledgementRepository;
import com.coreintra.approval.repository.EmploymentRulesRepresentativeRepository;
import com.coreintra.approval.repository.EmploymentRulesRepository;
import com.coreintra.approval.repository.EmploymentRulesSectionRepository;
import com.coreintra.approval.rules.EmploymentRules;
import com.coreintra.approval.service.EmploymentRulesStore;
import com.coreintra.compat.Immutables;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores 취업규칙 versions across the four tables V14 added.
 *
 * <h2>Reading re-runs the invariant</h2>
 *
 * <p>Rehydration goes through {@link EmploymentRules#publish}, the only
 * constructor there is, with the approval id, the mode and the signatures that
 * were recorded when the version was enacted. That is not ceremony: a row edited
 * by hand — in psql, during a support session, by a restore of a doctored dump —
 * into something that was never approved by enough representatives fails to load
 * at all, loudly, instead of quietly becoming the text employees are told they
 * are bound by. The schema makes writing such a row hard; this makes reading one
 * impossible.
 *
 * <p>The state passed to {@code publish} is {@code APPROVED} unconditionally,
 * and it is honest: {@code employment_rules} has a composite foreign key into
 * {@code approval_document (id, state)} with a CHECK pinning it to
 * {@code APPROVED}, so a row that exists was approved. The state is not
 * re-derived by reading the approval document, because a document whose state
 * somehow moved on afterwards must not retroactively un-enact rules people have
 * been working under.
 */
@Component
public class JpaEmploymentRulesStore implements EmploymentRulesStore {

    private final EmploymentRulesRepository versions;
    private final EmploymentRulesSectionRepository sections;
    private final EmploymentRulesRepresentativeRepository representatives;
    private final EmploymentRulesAcknowledgementRepository acknowledgements;

    public JpaEmploymentRulesStore(EmploymentRulesRepository versions,
            EmploymentRulesSectionRepository sections,
            EmploymentRulesRepresentativeRepository representatives,
            EmploymentRulesAcknowledgementRepository acknowledgements) {
        this.versions = versions;
        this.sections = sections;
        this.representatives = representatives;
        this.acknowledgements = acknowledgements;
    }

    @Override
    @Transactional(readOnly = true)
    public List<EmploymentRules> findAllVersions(String companyId) {
        List<EmploymentRulesEntity> rows = versions.findByCompanyIdOrderByVersionAsc(companyId);
        if (rows.isEmpty()) {
            return Immutables.listOf();
        }
        List<String> ids = new ArrayList<String>();
        for (EmploymentRulesEntity row : rows) {
            ids.add(row.id());
        }
        // Three queries for the whole history rather than three per version:
        // the 취업규칙 screen shows every version, and the diff walks pairs.
        Map<String, List<EmploymentRulesSectionEntity>> sectionsById =
                groupSections(sections.findByRulesIdInOrderBySortOrderAsc(ids));
        Map<String, List<EmploymentRulesRepresentativeEntity>> signersById =
                groupSigners(representatives.findByRulesIdInOrderBySortOrderAsc(ids));

        List<EmploymentRules> assembled = new ArrayList<EmploymentRules>();
        for (EmploymentRulesEntity row : rows) {
            assembled.add(assemble(row, sectionsById.get(row.id()), signersById.get(row.id())));
        }
        return Immutables.copyOf(assembled);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<EmploymentRules> findEffectiveOn(String companyId, LocalDate on) {
        if (on == null) {
            return Optional.empty();
        }
        EmploymentRulesEntity best = null;
        for (EmploymentRulesEntity row : versions.findByCompanyIdOrderByVersionAsc(companyId)) {
            if (row.effectiveFrom().isAfter(on)) {
                // A version taking effect next month does not bind anyone today.
                continue;
            }
            if (best == null || !row.effectiveFrom().isBefore(best.effectiveFrom())) {
                // Ties on the effective date go to the higher version, which the
                // ordering of the query hands us last.
                best = row;
            }
        }
        if (best == null) {
            return Optional.empty();
        }
        return Optional.of(assemble(best,
                sections.findByRulesIdOrderBySortOrderAsc(best.id()),
                representatives.findByRulesIdOrderBySortOrderAsc(best.id())));
    }

    @Override
    @Transactional(readOnly = true)
    public int nextVersionNumber(String companyId) {
        int highest = 0;
        for (EmploymentRulesEntity row : versions.findByCompanyIdOrderByVersionAsc(companyId)) {
            if (row.version() > highest) {
                highest = row.version();
            }
        }
        return highest + 1;
    }

    @Override
    @Transactional
    public void save(EmploymentRules rules) {
        versions.save(new EmploymentRulesEntity(rules.id(), rules.companyId(), rules.version(),
                rules.effectiveFrom(), rules.approvalDocumentId(), rules.approvedUnderMode()));

        int order = 0;
        for (EmploymentRules.Section section : rules.sections()) {
            sections.save(new EmploymentRulesSectionEntity(rules.id(), section.number(), order++,
                    section.headingKo(), section.headingEn(), section.bodyKo(),
                    section.bodyEn()));
        }
        int signer = 0;
        for (String accountId : rules.approvingRepresentativeIds()) {
            representatives.save(new EmploymentRulesRepresentativeEntity(
                    rules.id(), accountId, signer++));
        }
    }

    @Override
    @Transactional
    public void acknowledge(String rulesId, String employeeId, LocalDate acknowledgedOn) {
        EmploymentRulesAcknowledgementEntity.Key key =
                new EmploymentRulesAcknowledgementEntity.Key(rulesId, employeeId);
        if (acknowledgements.existsById(key)) {
            // Idempotent, and the first date is the one that stands: a second
            // click months later must not become the date on the receipt.
            return;
        }
        acknowledgements.save(new EmploymentRulesAcknowledgementEntity(
                rulesId, employeeId, acknowledgedOn));
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> acknowledgedBy(String rulesId) {
        List<String> employeeIds = new ArrayList<String>();
        for (EmploymentRulesAcknowledgementEntity row
                : acknowledgements.findByRulesIdOrderByEmployeeIdAsc(rulesId)) {
            employeeIds.add(row.employeeId());
        }
        return Immutables.copyOf(employeeIds);
    }

    private EmploymentRules assemble(EmploymentRulesEntity row,
            List<EmploymentRulesSectionEntity> sectionRows,
            List<EmploymentRulesRepresentativeEntity> signerRows) {

        List<EmploymentRules.Section> assembledSections =
                new ArrayList<EmploymentRules.Section>();
        if (sectionRows != null) {
            for (EmploymentRulesSectionEntity section : sectionRows) {
                assembledSections.add(new EmploymentRules.Section(section.sectionNumber(),
                        section.headingKo(), section.headingEn(), section.bodyKo(),
                        section.bodyEn()));
            }
        }
        List<String> signers = new ArrayList<String>();
        if (signerRows != null) {
            for (EmploymentRulesRepresentativeEntity signer : signerRows) {
                signers.add(signer.accountId());
            }
        }
        return EmploymentRules.publish(row.id(), row.companyId(), row.version(),
                row.effectiveFrom(), assembledSections, row.approvalDocumentId(),
                ApprovalState.APPROVED, row.approvedUnder(), signers);
    }

    private Map<String, List<EmploymentRulesSectionEntity>> groupSections(
            List<EmploymentRulesSectionEntity> rows) {
        Map<String, List<EmploymentRulesSectionEntity>> byRules =
                new LinkedHashMap<String, List<EmploymentRulesSectionEntity>>();
        for (EmploymentRulesSectionEntity row : rows) {
            List<EmploymentRulesSectionEntity> forRules = byRules.get(row.rulesId());
            if (forRules == null) {
                forRules = new ArrayList<EmploymentRulesSectionEntity>();
                byRules.put(row.rulesId(), forRules);
            }
            forRules.add(row);
        }
        return byRules;
    }

    private Map<String, List<EmploymentRulesRepresentativeEntity>> groupSigners(
            List<EmploymentRulesRepresentativeEntity> rows) {
        Map<String, List<EmploymentRulesRepresentativeEntity>> byRules =
                new LinkedHashMap<String, List<EmploymentRulesRepresentativeEntity>>();
        for (EmploymentRulesRepresentativeEntity row : rows) {
            List<EmploymentRulesRepresentativeEntity> forRules = byRules.get(row.rulesId());
            if (forRules == null) {
                forRules = new ArrayList<EmploymentRulesRepresentativeEntity>();
                byRules.put(row.rulesId(), forRules);
            }
            forRules.add(row);
        }
        return byRules;
    }
}
