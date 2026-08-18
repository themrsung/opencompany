package com.coreintra.auth.entity;

import java.io.Serializable;
import java.time.OffsetDateTime;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.IdClass;
import javax.persistence.Table;

/**
 * A TOTP counter this account has already used.
 *
 * <p>The composite primary key does the real work: two concurrent requests
 * presenting the same code both try to insert the same row, and exactly one
 * wins. Checking-then-inserting in application code would let both through
 * under load, which is precisely when someone is replaying a code.
 *
 * <p>Rows are pruned by a scheduled job once their step is outside any possible
 * drift window; keeping them forever would grow the table without adding
 * protection.
 */
@Entity
@Table(name = "consumed_time_step")
@IdClass(ConsumedTimeStep.Key.class)
public class ConsumedTimeStep {

    /** Composite key. Must be {@link Serializable} with equals/hashCode for JPA. */
    public static class Key implements Serializable {
        private static final long serialVersionUID = 1L;

        private String accountId;
        private long timeStep;

        public Key() {
        }

        public Key(String accountId, long timeStep) {
            this.accountId = accountId;
            this.timeStep = timeStep;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof Key)) {
                return false;
            }
            Key other = (Key) obj;
            return timeStep == other.timeStep
                    && (accountId == null ? other.accountId == null : accountId.equals(other.accountId));
        }

        @Override
        public int hashCode() {
            return (accountId == null ? 0 : accountId.hashCode()) * 31 + (int) (timeStep ^ (timeStep >>> 32));
        }
    }

    @Id
    @Column(name = "account_id", length = 36)
    private String accountId;

    @Id
    @Column(name = "time_step", nullable = false)
    private long timeStep;

    @Column(name = "consumed_at", nullable = false)
    private OffsetDateTime consumedAt;

    protected ConsumedTimeStep() {
    }

    public ConsumedTimeStep(String accountId, long timeStep) {
        this.accountId = accountId;
        this.timeStep = timeStep;
        this.consumedAt = OffsetDateTime.now();
    }

    public String accountId() {
        return accountId;
    }

    public long timeStep() {
        return timeStep;
    }

    public OffsetDateTime consumedAt() {
        return consumedAt;
    }
}
