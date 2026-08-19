package com.coreintra.approval.service;

import com.coreintra.approval.domain.ApprovalLineTemplate;
import java.util.List;

/**
 * Reads 결재선 templates.
 *
 * <p>A port rather than a Spring Data repository because
 * {@code approval_line_template} and {@code approval_template_step} (V4) have no
 * JPA entities yet. The interface is deliberately dumb — "give me the active
 * templates for this document type in this company" — so that the specificity
 * rule stays in {@link ApprovalLineTemplateService}, where it can be tested
 * without a database, rather than being encoded in a query nobody reads.
 */
public interface ApprovalLineTemplateStore {

    /**
     * Every active template for this document type, company-wide.
     *
     * <p>Includes the company default (the one with a null
     * {@link ApprovalLineTemplate#orgUnitId()}) alongside unit-specific ones.
     * The set is small by nature: one per unit that has chosen to differ.
     */
    List<ApprovalLineTemplate> findActive(String companyId, String documentType);
}
