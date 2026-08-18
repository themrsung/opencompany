package com.coreintra.approval.service;

import com.coreintra.approval.domain.ApprovalLineTemplate;
import com.coreintra.compat.Immutables;
import com.coreintra.compat.Texts;
import com.coreintra.core.org.OrgUnit;
import com.coreintra.core.org.repository.OrgUnitRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Picks the 결재선 template a document should use.
 *
 * <h2>Most specific wins, and specificity is measured up the tree</h2>
 *
 * <p>A template attached to the drafter's own unit beats one attached to its
 * parent, which beats the company default. That ordering is the whole point of
 * per-unit templates: 개발팀 can require its own 팀장 review without 총무팀 also
 * getting one, and a company that has configured nothing still gets a line.
 *
 * <p>Walking up the tree rather than matching only the exact unit matters for
 * the common case where a company configures one template on 본부 and expects
 * every team under it to inherit. Matching exactly would silently fall through
 * to the company default and route the document past the 본부장.
 *
 * <p>The amount thresholds are the template's own concern
 * ({@link ApprovalLineTemplate#stepsFor(BigDecimal)}); this service only decides
 * <em>which</em> template, never how many steps it produces.
 */
@Service
public class ApprovalLineTemplateService {

    /** No template covers this document type. Refused with the fix named. */
    public static class NoTemplateException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public NoTemplateException(String message) {
            super(message);
        }
    }

    private final ApprovalLineTemplateStore templates;
    private final OrgUnitRepository orgUnits;

    public ApprovalLineTemplateService(ApprovalLineTemplateStore templates,
            OrgUnitRepository orgUnits) {
        this.templates = templates;
        this.orgUnits = orgUnits;
    }

    /**
     * The template for this document type as seen from this org unit.
     *
     * @param orgUnitId the drafter's unit; null falls straight through to the
     *        company default
     * @throws NoTemplateException if not even a company default exists — a
     *         submission with no line would be an approval nobody has to give
     */
    @Transactional(readOnly = true)
    public ApprovalLineTemplate resolve(String companyId, String documentType, String orgUnitId) {
        Optional<ApprovalLineTemplate> found = find(companyId, documentType, orgUnitId);
        if (!found.isPresent()) {
            throw new NoTemplateException(
                    "\"" + documentType + "\" 문서의 결재선 서식이 없습니다. 회사 기본 결재선을 먼저 "
                            + "등록해 주십시오. (No approval line template covers document type \""
                            + documentType + "\" in company " + companyId + ", and there is no "
                            + "company default. Register one before this document type can be "
                            + "submitted.)");
        }
        return found.get();
    }

    /** As {@link #resolve}, but empty rather than throwing. For "can this be submitted?" checks. */
    @Transactional(readOnly = true)
    public Optional<ApprovalLineTemplate> find(String companyId, String documentType,
            String orgUnitId) {
        List<ApprovalLineTemplate> candidates = templates.findActive(companyId, documentType);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        for (String unitId : selfAndAncestors(orgUnitId)) {
            for (ApprovalLineTemplate candidate : candidates) {
                if (unitId.equals(candidate.orgUnitId())) {
                    return Optional.of(candidate);
                }
            }
        }
        for (ApprovalLineTemplate candidate : candidates) {
            if (candidate.orgUnitId() == null) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * The unit itself, then each ancestor in turn.
     *
     * <p>Guarded against a cycle in {@code parent_id}. A cycle is a data fault
     * rather than something to model, but discovering it as a hung submission
     * instead of a bounded walk would be a bad afternoon.
     */
    private List<String> selfAndAncestors(String orgUnitId) {
        List<String> chain = new ArrayList<String>();
        String current = orgUnitId;
        int guard = 0;
        while (Texts.hasText(current) && guard++ < 64) {
            if (chain.contains(current)) {
                break;
            }
            chain.add(current);
            Optional<OrgUnit> unit = orgUnits.findById(current);
            if (!unit.isPresent()) {
                break;
            }
            current = unit.get().parentId();
        }
        return Immutables.copyOf(chain);
    }
}
