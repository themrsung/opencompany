package com.coreintra.approval.service;

import com.coreintra.core.permission.PermissionTarget;
import java.io.Serializable;
import java.time.LocalDate;

/**
 * Everything role resolution needs to know about the document being submitted.
 *
 * <p>Exists so resolution is a pure function of the document plus a date, and
 * never of "now". A document submitted with a back-dated business date must
 * resolve against the org chart as it stood on <em>that</em> date, or a
 * back-dated 지출결의서 routes to whoever happens to hold the post today.
 */
public final class ApprovalContext implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String companyId;
    private final String drafterAccountId;
    private final String drafterEmployeeId;
    private final String drafterOrgUnitId;
    private final LocalDate businessDate;

    public ApprovalContext(String companyId, String drafterAccountId, String drafterEmployeeId,
            String drafterOrgUnitId, LocalDate businessDate) {
        if (companyId == null) {
            throw new NullPointerException("companyId");
        }
        if (businessDate == null) {
            throw new NullPointerException(
                    "businessDate is required; resolution is always as of a date, never as of now");
        }
        this.companyId = companyId;
        this.drafterAccountId = drafterAccountId;
        this.drafterEmployeeId = drafterEmployeeId;
        this.drafterOrgUnitId = drafterOrgUnitId;
        this.businessDate = businessDate;
    }

    public String companyId() {
        return companyId;
    }

    public String drafterAccountId() {
        return drafterAccountId;
    }

    /** Null for an account with no employee record, e.g. a service account. */
    public String drafterEmployeeId() {
        return drafterEmployeeId;
    }

    /** Null when the drafter holds no position; 부서 domains then resolve to nothing. */
    public String drafterOrgUnitId() {
        return drafterOrgUnitId;
    }

    public LocalDate businessDate() {
        return businessDate;
    }

    /**
     * The permission target for decisions about this document.
     *
     * <p>Built from the document's own company, org unit, owner and
     * <em>business date</em> — not today's. A check on a past-dated document has
     * to see the org as it was, which is exactly what the evaluator does with
     * {@link PermissionTarget#asOfBusinessDate()}.
     */
    public PermissionTarget target(String description) {
        return PermissionTarget.builder()
                .companyId(companyId)
                .orgUnitId(drafterOrgUnitId)
                .ownerEmployeeId(drafterEmployeeId)
                .asOfBusinessDate(businessDate)
                .description(description)
                .build();
    }
}
