package com.coreintra.approval.repository;

import com.coreintra.approval.entity.ApprovalLineTemplateEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalLineTemplateRepository
        extends JpaRepository<ApprovalLineTemplateEntity, String> {

    /**
     * Every live template for a document type, company-wide.
     *
     * <p>Matches {@code approval_template_lookup_idx}. The set is small by
     * nature — the company default plus one per unit that has chosen to differ —
     * and picking the most specific one is deliberately not done here: that rule
     * walks the org tree, and it belongs in the service where it is tested
     * without a database.
     */
    List<ApprovalLineTemplateEntity> findByCompanyIdAndDocumentTypeAndActiveTrue(
            String companyId, String documentType);
}
