package com.coreintra.runtime.support;

import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.runtime.audit.AuditAction;
import com.coreintra.runtime.audit.AuditActorKind;
import com.coreintra.runtime.audit.AuditEvent;
import com.coreintra.runtime.audit.AuditLogService;
import com.coreintra.runtime.audit.AuditOutcome;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;

/**
 * The doubled auditing §8 requires, made structural.
 *
 * <p>§8 says every request under a support session is logged, <em>including
 * reads</em>. Written as a rule, that is a rule someone forgets on the one
 * endpoint that matters. Written as this class, the only way to ask whether a
 * support session may do something is to go through a call that also records
 * that it asked - and records the refusal too, which is the more interesting
 * row.
 *
 * <p>This is not a second permission system and does not implement
 * {@code PermissionEvaluator}. It answers one narrower question: is this
 * capability ticked on a live grant. A support session must clear both this and
 * the ordinary evaluator, and the intersection is deliberate - a temporary
 * master can never exceed what a master could do, only be a subset of it.
 */
@Service
public class TemporaryMasterAccessGate {

    private final TemporaryMasterService sessions;
    private final AuditLogService audit;
    private final Clock clock;

    public TemporaryMasterAccessGate(TemporaryMasterService sessions, AuditLogService audit,
                                     Clock clock) {
        this.sessions = sessions;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Decides, and records the decision either way.
     *
     * @param rowsTouched how many rows the caller is about to read or change.
     *        One and four thousand are different events, and the difference is
     *        the whole reason an export warning exists.
     * @return true when the capability is ticked on a live grant for this account
     */
    public boolean permits(String supportAccountId, String capability, String resource,
                           String resourceId, AuditAction action, int rowsTouched,
                           RequestContext request) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        TemporaryMasterGrantRow grant = sessions.grantFor(supportAccountId);
        if (grant == null) {
            // Not a support session at all. Nothing to double-audit here: the
            // ordinary path logs it, and inventing a company id to file this
            // under would put a row in someone else's trail.
            return false;
        }
        boolean allowed = grant.allowsAt(capability, now);
        audit.record(AuditEvent.builder()
                .companyId(grant.companyId())
                .actor(supportAccountId, AuditActorKind.TEMPORARY_MASTER, grant.engineerName())
                .capability(capability)
                .target(resource, resourceId)
                .action(action)
                .outcome(allowed ? AuditOutcome.ALLOWED : AuditOutcome.DENIED)
                .rowsTouched(allowed ? rowsTouched : 0)
                .occurredAt(request == null ? BusinessInstant.startOfDay(now.toLocalDate())
                        : request.occurredAt())
                .request(request == null ? null : request.requestId(),
                        request == null ? null : request.ipAddress(),
                        request == null ? null : request.userAgent())
                .underTemporaryMaster(grant.id())
                .build());
        return allowed;
    }

    /** What the transport layer knows and the domain does not. */
    public static final class RequestContext {

        private final BusinessInstant occurredAt;
        private final String requestId;
        private final String ipAddress;
        private final String userAgent;

        public RequestContext(BusinessInstant occurredAt, String requestId, String ipAddress,
                              String userAgent) {
            if (occurredAt == null) {
                throw new IllegalArgumentException(
                        "a request happens on a business day; the UTC clock is recorded separately");
            }
            this.occurredAt = occurredAt;
            this.requestId = requestId;
            this.ipAddress = ipAddress;
            this.userAgent = userAgent;
        }

        public BusinessInstant occurredAt() {
            return occurredAt;
        }

        public String requestId() {
            return requestId;
        }

        public String ipAddress() {
            return ipAddress;
        }

        public String userAgent() {
            return userAgent;
        }
    }
}
