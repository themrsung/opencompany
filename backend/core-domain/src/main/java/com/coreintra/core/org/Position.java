package com.coreintra.core.org;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * The assignment tuple: who, in which unit, at which rank, doing which
 * functions, between which dates.
 *
 * <h2>History is preserved, not overwritten</h2>
 *
 * <p>A promotion closes the old row with an {@code effectiveTo} and opens a new
 * one. Nothing is updated in place. That is what makes a permission check on a
 * past-dated document resolve against the org as it stood on that document's
 * business date — the reason approvals from before a reorganisation remain
 * auditable and re-checkable.
 *
 * <p>{@code effectiveTo} is <b>exclusive</b>, so a position ending 2026-06-01
 * and one starting 2026-06-01 do not both apply on that day. Half-open
 * intervals are the only way to make adjacency unambiguous.
 *
 * <p>Job functions live in a join table rather than here: an assignment can
 * carry several, and they change independently of the rank.
 */
@Entity
@Table(name = "position")
public class Position {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "employee_id", nullable = false, length = 36)
    private String employeeId;

    @Column(name = "org_unit_id", nullable = false, length = 36)
    private String orgUnitId;

    @Column(name = "rank_id", nullable = false, length = 36)
    private String rankId;

    /**
     * The employee's primary assignment when they hold several.
     *
     * <p>Only affects display and the default org unit on a new document.
     * Permission scope considers every concurrent position, not just this one.
     */
    @Column(name = "primary_position", nullable = false)
    private boolean primaryPosition = true;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    /** Exclusive. Null means open-ended. */
    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected Position() {
    }

    public Position(String id, String employeeId, String orgUnitId, String rankId,
            LocalDate effectiveFrom) {
        if (effectiveFrom == null) {
            throw new NullPointerException("effectiveFrom");
        }
        this.id = id;
        this.employeeId = employeeId;
        this.orgUnitId = orgUnitId;
        this.rankId = rankId;
        this.effectiveFrom = effectiveFrom;
        this.createdAt = OffsetDateTime.now();
    }

    /** Active on {@code date}: from is inclusive, to is exclusive. */
    public boolean isActiveOn(LocalDate date) {
        if (date.isBefore(effectiveFrom)) {
            return false;
        }
        return effectiveTo == null || date.isBefore(effectiveTo);
    }

    /**
     * Closes this assignment the day {@code newFrom} begins.
     *
     * @throws IllegalArgumentException if that would end it before it started
     */
    public void closeOn(LocalDate newFrom) {
        if (newFrom.isBefore(effectiveFrom)) {
            throw new IllegalArgumentException(
                    "cannot end a position on " + newFrom + "; it started on " + effectiveFrom);
        }
        this.effectiveTo = newFrom;
    }

    public String id() {
        return id;
    }

    public String employeeId() {
        return employeeId;
    }

    public String orgUnitId() {
        return orgUnitId;
    }

    public String rankId() {
        return rankId;
    }

    public boolean isPrimary() {
        return primaryPosition;
    }

    public void setPrimary(boolean value) {
        this.primaryPosition = value;
    }

    public LocalDate effectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate effectiveTo() {
        return effectiveTo;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }
}
