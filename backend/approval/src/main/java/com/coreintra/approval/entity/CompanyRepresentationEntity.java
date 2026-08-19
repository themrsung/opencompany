package com.coreintra.approval.entity;

import com.coreintra.approval.domain.RepresentationMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * A company's 대표 representation mode over an interval of business dates.
 *
 * <p>Effective-dated rather than current, and rows are added rather than
 * edited. A document submitted in March and approved in May was routed under
 * March's mode; re-reading "the current mode" would retroactively invalidate an
 * approval that was complete under the rules in force when it was given.
 *
 * <p>The quorum is stored as two numbers rather than as a rendered label so
 * that {@link RepresentationMode} can be rebuilt exactly. Under {@code JOINT}
 * the required count is at least 2 — a joint mode satisfiable by one person is
 * 각자대표 wearing a different label — and the database enforces that
 * independently of this class.
 */
@Entity
@Table(name = "company_representation")
public class CompanyRepresentationEntity {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "company_id", nullable = false, length = 36)
    private String companyId;

    @Column(name = "mode", nullable = false, length = 16)
    private String mode;

    @Column(name = "required_approvals", nullable = false)
    private int requiredApprovals;

    @Column(name = "designated_representatives", nullable = false)
    private int designatedRepresentatives;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    /** Null while this is the arrangement still in force. */
    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected CompanyRepresentationEntity() {
    }

    public CompanyRepresentationEntity(String id, String companyId, RepresentationMode mode,
            LocalDate effectiveFrom, LocalDate effectiveTo) {
        this.id = id;
        this.companyId = companyId;
        this.mode = mode.kind().name();
        this.requiredApprovals = mode.requiredApprovals();
        this.designatedRepresentatives = mode.designatedRepresentatives();
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
        this.createdAt = OffsetDateTime.now();
    }

    /**
     * Rebuilds the domain value.
     *
     * <p>Goes through the domain factories, so a row whose numbers were edited
     * by hand into a quorum of 1 under JOINT fails here rather than quietly
     * downgrading the company to single-signature approval.
     */
    public RepresentationMode toMode() {
        if (RepresentationMode.Kind.JOINT.name().equals(mode)) {
            return RepresentationMode.joint(requiredApprovals, designatedRepresentatives);
        }
        return RepresentationMode.several(designatedRepresentatives);
    }

    /** Half-open: {@code effectiveTo} is the first date no longer covered. */
    public boolean coversBusinessDate(LocalDate date) {
        if (date == null || effectiveFrom == null || date.isBefore(effectiveFrom)) {
            return false;
        }
        return effectiveTo == null || date.isBefore(effectiveTo);
    }

    public String id() {
        return id;
    }

    public String companyId() {
        return companyId;
    }

    public String mode() {
        return mode;
    }

    public int requiredApprovals() {
        return requiredApprovals;
    }

    public int designatedRepresentatives() {
        return designatedRepresentatives;
    }

    public LocalDate effectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate effectiveTo() {
        return effectiveTo;
    }

    /** Closes this arrangement. The successor is a new row, never an edit. */
    public void closeOn(LocalDate date) {
        this.effectiveTo = date;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }
}
