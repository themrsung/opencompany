package com.coreintra.runtime.ratelimit;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * How many calls, over how long, for whom (§10).
 *
 * <p>Per account and per key, both, because they answer different questions: an
 * account limit protects a company from one of its own users, and a key limit
 * protects everyone from one runaway integration holding a key that account
 * shares.
 *
 * <p>Policies are persisted; counters are not. A limit is a protection against
 * a runaway caller, not an accounting record, and writing a row per request
 * would make the database the first thing to fall over under exactly the load
 * the limit exists to survive.
 */
@Entity
@Table(name = "rate_limit_policy")
public class RateLimitPolicyRow {

    public static final String ACCOUNT = "ACCOUNT";
    public static final String API_KEY = "API_KEY";

    /** The fallback when no policy names this subject. */
    public static final String DEFAULT = "DEFAULT";

    @Id
    @Column(name = "id", length = 36)
    private String id;

    /** Null means an installation-wide default rather than one company's. */
    @Column(name = "company_id", length = 36)
    private String companyId;

    @Column(name = "subject_kind", nullable = false, length = 16)
    private String subjectKind;

    @Column(name = "subject_id", length = 36)
    private String subjectId;

    /** What is being limited: a bucket name, not a URL. */
    @Column(name = "limit_key", nullable = false, length = 120)
    private String limitKey;

    @Column(name = "permits", nullable = false)
    private int permits;

    @Column(name = "window_seconds", nullable = false)
    private int windowSeconds;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "retired_at")
    private OffsetDateTime retiredAt;

    /** JPA requires a no-arg constructor. Not for application use. */
    protected RateLimitPolicyRow() {
    }

    public RateLimitPolicyRow(String id, String companyId, String subjectKind, String subjectId,
                              String limitKey, int permits, Duration window,
                              OffsetDateTime createdAt) {
        if (DEFAULT.equals(subjectKind) != (subjectId == null)) {
            throw new IllegalArgumentException(
                    "a DEFAULT policy names no subject, and a subject policy must name one");
        }
        if (permits < 1) {
            throw new IllegalArgumentException(
                    "a limit of zero is a closed door, which is a permission decision, not a "
                            + "rate limit");
        }
        if (window == null || window.getSeconds() < 1 || window.getSeconds() > 86400) {
            throw new IllegalArgumentException("a rate limit window runs from 1 second to a day");
        }
        this.id = id;
        this.companyId = companyId;
        this.subjectKind = subjectKind;
        this.subjectId = subjectId;
        this.limitKey = limitKey;
        this.permits = permits;
        this.windowSeconds = (int) window.getSeconds();
        this.createdAt = createdAt;
    }

    public String id() {
        return id;
    }

    public String companyId() {
        return companyId;
    }

    public String subjectKind() {
        return subjectKind;
    }

    public String subjectId() {
        return subjectId;
    }

    public String limitKey() {
        return limitKey;
    }

    public int permits() {
        return permits;
    }

    public Duration window() {
        return Duration.ofSeconds(windowSeconds);
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public OffsetDateTime retiredAt() {
        return retiredAt;
    }

    public boolean isLive() {
        return retiredAt == null;
    }

    /** Superseded rather than edited, so the history of what applied stays readable. */
    public void retire(OffsetDateTime at) {
        if (retiredAt == null) {
            this.retiredAt = at;
        }
    }
}
