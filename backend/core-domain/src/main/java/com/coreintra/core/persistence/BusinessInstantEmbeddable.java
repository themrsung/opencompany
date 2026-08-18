package com.coreintra.core.persistence;

import com.coreintra.businesstime.BusinessInstant;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import javax.persistence.Column;
import javax.persistence.Embeddable;

/**
 * JPA mapping for {@link BusinessInstant}.
 *
 * <p>The domain type itself stays free of persistence annotations — it lives in
 * {@code business-time}, which depends on nothing. This class is the only place
 * that knows how a business instant becomes columns.
 *
 * <h2>Column layout</h2>
 *
 * <pre>
 *   business_date   DATE                     not null
 *   offset_seconds  business_offset_seconds  not null   (domain-checked window)
 *   absolute_ts     TIMESTAMP                generated, read-only
 * </pre>
 *
 * <p>An entity with more than one instant overrides the names:
 *
 * <pre>{@code
 * @Embedded
 * @AttributeOverrides({
 *     @AttributeOverride(name = "businessDate",  column = @Column(name = "started_business_date")),
 *     @AttributeOverride(name = "offsetSeconds", column = @Column(name = "started_offset_seconds")),
 *     @AttributeOverride(name = "absoluteTs",    column = @Column(name = "started_absolute_ts",
 *                                                                 insertable = false, updatable = false))
 * })
 * private BusinessInstantEmbeddable startedAt;
 * }</pre>
 *
 * <p>{@code absoluteTs} is written by the database, never by us — see the
 * migration's rationale. It is exposed here only so range-scan queries can
 * project it, and it is never an ordering key.
 */
@Embeddable
public class BusinessInstantEmbeddable implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "offset_seconds", nullable = false)
    private Integer offsetSeconds;

    /** Database-generated. Present for range scans; never an ordering key. */
    @Column(name = "absolute_ts", insertable = false, updatable = false)
    private LocalDateTime absoluteTs;

    /** JPA requires a no-arg constructor. Not for application use. */
    protected BusinessInstantEmbeddable() {
    }

    private BusinessInstantEmbeddable(LocalDate businessDate, Integer offsetSeconds) {
        this.businessDate = businessDate;
        this.offsetSeconds = offsetSeconds;
    }

    public static BusinessInstantEmbeddable from(BusinessInstant instant) {
        if (instant == null) {
            return null;
        }
        return new BusinessInstantEmbeddable(
                instant.businessDate(), Integer.valueOf(instant.offsetSeconds()));
    }

    /**
     * Rebuilds the domain value.
     *
     * <p>Re-validates the window on the way out. A row written by a migration or
     * a support session that somehow bypassed the domain constraint should fail
     * loudly here rather than becoming an out-of-range value in memory.
     */
    public BusinessInstant toBusinessInstant() {
        if (businessDate == null || offsetSeconds == null) {
            return null;
        }
        return BusinessInstant.of(businessDate, offsetSeconds.intValue());
    }

    public LocalDate businessDate() {
        return businessDate;
    }

    public Integer offsetSeconds() {
        return offsetSeconds;
    }

    /** The database-derived wall-clock moment. Null until the row is loaded. */
    public LocalDateTime absoluteTs() {
        return absoluteTs;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof BusinessInstantEmbeddable)) {
            return false;
        }
        BusinessInstantEmbeddable other = (BusinessInstantEmbeddable) obj;
        // absoluteTs is derived, so it is deliberately excluded: two values with
        // the same date and offset are the same instant whether or not either
        // has been round-tripped through the database yet.
        if (businessDate == null ? other.businessDate != null : !businessDate.equals(other.businessDate)) {
            return false;
        }
        return offsetSeconds == null ? other.offsetSeconds == null : offsetSeconds.equals(other.offsetSeconds);
    }

    @Override
    public int hashCode() {
        int result = businessDate == null ? 0 : businessDate.hashCode();
        return result * 31 + (offsetSeconds == null ? 0 : offsetSeconds.hashCode());
    }

    @Override
    public String toString() {
        BusinessInstant instant = toBusinessInstant();
        return instant == null ? "BusinessInstant(empty)" : instant.toWireString();
    }
}
