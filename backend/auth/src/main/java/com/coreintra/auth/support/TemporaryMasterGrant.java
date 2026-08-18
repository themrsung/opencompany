package com.coreintra.auth.support;

import com.coreintra.approval.domain.ApprovalState;
import com.coreintra.approval.domain.RepresentationMode;
import com.coreintra.compat.Immutables;
import com.coreintra.compat.Texts;
import java.io.Serializable;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A time-boxed support session: the vendor helps without holding standing
 * god-rights.
 *
 * <p>The highest-risk feature in the system, and the one whose defaults matter
 * most. Every property below exists because the alternative is a support
 * account that quietly becomes permanent.
 *
 * <h2>Off by default, and there is no grant-all</h2>
 *
 * <p>A grant with nothing ticked can read nothing — not one row, not the
 * company's own name. Capabilities are ticked individually and wildcards are
 * refused at construction, not merely hidden in the UI: an API caller who knows
 * the capability names gets exactly the same answer. A "grant all" button is the
 * one feature that would undo this design, because under time pressure it is
 * always the button that gets pressed.
 *
 * <h2>Expiry is checked, never swept</h2>
 *
 * <p>{@link #allowsAt} takes the current time and enforces the deadline itself.
 * A background revocation job is still useful for killing live sessions
 * promptly, but access does not depend on it running — a sweep that is late,
 * stuck or dead must not extend a support session by one second.
 *
 * <p>Immutable. There is no extension and no renewal: issue a new grant, which
 * means a new approval and a new reason.
 */
public final class TemporaryMasterGrant implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final Duration DEFAULT_TTL = Duration.ofHours(4);
    public static final Duration MAX_TTL = Duration.ofHours(24);

    /**
     * Capabilities that may never be granted to a support session, whatever
     * anyone ticks.
     *
     * <p>Each would let the session escape its own boundaries: grant itself
     * more, make itself permanent, mint another session, or remove the record
     * of what it did.
     */
    private static final Set<String> NEVER_GRANTABLE = Immutables.setOf(
            "admin.permission:grant",
            "admin.permission:revoke",
            "admin.master:create",
            "admin.master:update",
            "admin.master:delete",
            "admin.temporaryMaster:issue",
            "hr.employmentRules:amend",
            "hr.employmentRules:create",
            "hr.employmentRules:repeal",
            "company.representation:update",
            "admin.audit:disable",
            "admin.audit:delete");

    /**
     * What each capability actually exposes, in end-user terms.
     *
     * <p>The person ticking these boxes is a client master, not an engineer.
     * "read every employee's salary history" is a decision someone can make;
     * {@code hr.compensation:read} is a string they will agree to because it
     * looks technical and specific.
     */
    private static final Map<String, String[]> PLAIN_LANGUAGE = buildPlainLanguage();

    private static Map<String, String[]> buildPlainLanguage() {
        Map<String, String[]> map = new LinkedHashMap<String, String[]>();
        map.put("hr.employee:read", new String[] {
                "모든 직원의 인사 기록을 열람합니다.",
                "Read every employee's HR record." });
        map.put("hr.employee:export", new String[] {
                "전체 직원 명부를 파일로 내보냅니다.",
                "Export the entire employee dataset as a file." });
        map.put("hr.leave:read", new String[] {
                "모든 직원의 휴가 사용 내역을 열람합니다.",
                "Read every employee's leave history." });
        map.put("approval.document:read", new String[] {
                "결재 문서의 내용과 결재 이력을 열람합니다.",
                "Read approval documents and their full approval history." });
        map.put("approval.document:update", new String[] {
                "결재 문서를 수정합니다.",
                "Modify approval documents." });
        map.put("accounting.entry:read", new String[] {
                "회계 전표와 원장을 열람합니다.",
                "Read journal entries and the general ledger." });
        map.put("company.settings:read", new String[] {
                "회사 설정을 열람합니다.",
                "Read company settings." });
        map.put("company.settings:update", new String[] {
                "회사 설정을 변경합니다.",
                "Change company settings." });
        map.put("attendance.record:read", new String[] {
                "모든 직원의 근태 기록을 열람합니다.",
                "Read every employee's attendance records." });
        return Immutables.mapCopyOf(map);
    }

    private final String id;
    private final String companyId;
    private final String companyName;
    private final String issuedByAccountId;
    private final String engineerName;
    private final String reason;
    private final String ticketReference;
    private final OffsetDateTime issuedAt;
    private final Duration timeToLive;
    private final Set<String> capabilities;
    private final String approvalDocumentId;
    private OffsetDateTime revokedAt;
    private String revokedByAccountId;

    private TemporaryMasterGrant(Builder builder) {
        this.id = builder.id;
        this.companyId = builder.companyId;
        this.companyName = builder.companyName;
        this.issuedByAccountId = builder.issuedByAccountId;
        this.engineerName = builder.engineerName;
        this.reason = builder.reason;
        this.ticketReference = builder.ticketReference;
        this.issuedAt = builder.issuedAt;
        this.timeToLive = builder.timeToLive;
        this.capabilities = Immutables.setCopyOf(builder.capabilities);
        this.approvalDocumentId = builder.approvalDocumentId;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String id() {
        return id;
    }

    public String companyId() {
        return companyId;
    }

    public String companyName() {
        return companyName;
    }

    public String issuedByAccountId() {
        return issuedByAccountId;
    }

    public String engineerName() {
        return engineerName;
    }

    /** Mandatory, and shown in the audit trail and the banner. */
    public String reason() {
        return reason;
    }

    public String ticketReference() {
        return ticketReference;
    }

    public OffsetDateTime issuedAt() {
        return issuedAt;
    }

    public Duration timeToLive() {
        return timeToLive;
    }

    public OffsetDateTime expiresAt() {
        return issuedAt.plus(timeToLive);
    }

    /** Exactly what was ticked. Never widened, never inferred. */
    public Set<String> capabilities() {
        return capabilities;
    }

    public String approvalDocumentId() {
        return approvalDocumentId;
    }

    /** Support sessions always audit reads as well as writes. */
    public boolean auditsReads() {
        return true;
    }

    public void revoke(String byAccountId, OffsetDateTime at) {
        if (revokedAt == null) {
            this.revokedAt = at;
            this.revokedByAccountId = byAccountId;
        }
    }

    public OffsetDateTime revokedAt() {
        return revokedAt;
    }

    public String revokedByAccountId() {
        return revokedByAccountId;
    }

    public boolean isActiveAt(OffsetDateTime now) {
        if (revokedAt != null && !now.isBefore(revokedAt)) {
            return false;
        }
        return !now.isBefore(issuedAt) && now.isBefore(expiresAt());
    }

    /** Capability check ignoring time. Prefer {@link #allowsAt}. */
    public boolean allows(String capability) {
        return capabilities.contains(capability);
    }

    /**
     * The real check: ticked <em>and</em> still within the window.
     *
     * <p>Deliberately requires the time, so no caller can accidentally ask the
     * question without it.
     */
    public boolean allowsAt(String capability, OffsetDateTime now) {
        return isActiveAt(now) && allows(capability);
    }

    /** What this session can see, in words a client master can act on. */
    public List<String> describeCapabilities(String locale) {
        int index = "en".equals(locale) ? 1 : 0;
        List<String> described = new ArrayList<String>();
        for (String capability : capabilities) {
            String[] text = PLAIN_LANGUAGE.get(capability);
            described.add(text == null
                    // An unmapped capability is described conservatively rather
                    // than shown as a bare id: unknown access is the alarming
                    // case, not the one to render most technically.
                    ? ("en".equals(locale)
                            ? "Access to " + capability + " (no plain-language description "
                                    + "available — treat as unrestricted within that resource)"
                            : capability + " 접근 (설명이 등록되지 않은 권한입니다. "
                                    + "해당 자원 내에서 제한이 없다고 간주해 주십시오.)")
                    : text[index]);
        }
        return Immutables.copyOf(described);
    }

    /** The persistent, non-dismissible banner every user in the company sees. */
    public Banner bannerAt(OffsetDateTime now, String locale) {
        if (!isActiveAt(now)) {
            return null;
        }
        Duration remaining = Duration.between(now, expiresAt());
        StringBuilder text = new StringBuilder();
        if ("en".equals(locale)) {
            text.append("Support session active: ").append(engineerName)
                .append(" is connected to ").append(companyName).append(". Access: ");
        } else {
            text.append("지원 세션이 진행 중입니다: ").append(engineerName)
                .append("님이 ").append(companyName).append("에 접속해 있습니다. 허용된 권한: ");
        }
        List<String> described = describeCapabilities(locale);
        text.append(described.isEmpty()
                ? ("en".equals(locale) ? "none" : "없음")
                : String.valueOf(described));
        return new Banner(text.toString(), remaining, expiresAt(), false, "revokeTemporaryMaster");
    }

    /** The visibly different chrome the engineer's own session carries. */
    public SessionChrome sessionChrome() {
        return new SessionChrome("#B3261E",
                "SUPPORT SESSION — " + companyName + " — expires " + expiresAt());
    }

    /** The report generated automatically on expiry or revocation. */
    public SessionReport reportFor(List<AuditedAction> actions, OffsetDateTime endedAt,
            String endedReason) {
        int rows = 0;
        for (AuditedAction action : actions) {
            rows += action.rowCount();
        }
        return new SessionReport(this, Immutables.copyOf(actions), rows, endedAt, endedReason);
    }

    /** The banner shown to everyone in the company while a session is live. */
    public static final class Banner implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String text;
        private final Duration remaining;
        private final OffsetDateTime expiresAt;
        private final boolean dismissible;
        private final String revokeAction;

        Banner(String text, Duration remaining, OffsetDateTime expiresAt, boolean dismissible,
                String revokeAction) {
            this.text = text;
            this.remaining = remaining;
            this.expiresAt = expiresAt;
            this.dismissible = dismissible;
            this.revokeAction = revokeAction;
        }

        public String text() {
            return text;
        }

        public Duration remaining() {
            return remaining;
        }

        public OffsetDateTime expiresAt() {
            return expiresAt;
        }

        /** Always false. A dismissible banner is a banner nobody sees. */
        public boolean dismissible() {
            return dismissible;
        }

        /** The Revoke now action, available to any master. */
        public String revokeAction() {
            return revokeAction;
        }
    }

    public static final class SessionChrome implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String accentColour;
        private final String headerText;

        SessionChrome(String accentColour, String headerText) {
            this.accentColour = accentColour;
            this.headerText = headerText;
        }

        public String accentColour() {
            return accentColour;
        }

        public String headerText() {
            return headerText;
        }
    }

    /** One audited action. Reads included — that is the point. */
    public static final class AuditedAction implements Serializable {
        private static final long serialVersionUID = 1L;

        private final OffsetDateTime at;
        private final String capability;
        private final String targetId;
        private final int rowCount;

        public AuditedAction(OffsetDateTime at, String capability, String targetId, int rowCount) {
            this.at = at;
            this.capability = capability;
            this.targetId = targetId;
            this.rowCount = rowCount;
        }

        public OffsetDateTime at() {
            return at;
        }

        public String capability() {
            return capability;
        }

        public String targetId() {
            return targetId;
        }

        public int rowCount() {
            return rowCount;
        }
    }

    /** Generated automatically on expiry or revocation, and sent to all masters. */
    public static final class SessionReport implements Serializable {
        private static final long serialVersionUID = 1L;

        private final TemporaryMasterGrant grant;
        private final List<AuditedAction> actions;
        private final int rowsTouched;
        private final OffsetDateTime endedAt;
        private final String endedReason;

        SessionReport(TemporaryMasterGrant grant, List<AuditedAction> actions, int rowsTouched,
                OffsetDateTime endedAt, String endedReason) {
            this.grant = grant;
            this.actions = actions;
            this.rowsTouched = rowsTouched;
            this.endedAt = endedAt;
            this.endedReason = endedReason;
        }

        public int totalActions() {
            return actions.size();
        }

        public int rowsTouched() {
            return rowsTouched;
        }

        public OffsetDateTime endedAt() {
            return endedAt;
        }

        /** "expired" or "revoked". */
        public String endedReason() {
            return endedReason;
        }

        public String reason() {
            return grant.reason();
        }

        public List<AuditedAction> actions() {
            return actions;
        }

        /** The report body. Produced even for a session that did nothing. */
        public String describe() {
            StringBuilder out = new StringBuilder();
            out.append("Support session report\n")
               .append("  Company:   ").append(grant.companyName()).append('\n')
               .append("  Engineer:  ").append(grant.engineerName()).append('\n')
               .append("  Reason:    ").append(grant.reason()).append('\n')
               .append("  Ticket:    ").append(grant.ticketReference() == null
                       ? "(none)" : grant.ticketReference()).append('\n')
               .append("  Issued:    ").append(grant.issuedAt()).append('\n')
               .append("  Ended:     ").append(endedAt).append(" (").append(endedReason).append(")\n")
               .append("  Granted:   ").append(grant.capabilities()).append('\n');

            if (actions.isEmpty()) {
                // "Nothing was accessed" is itself the answer a client wants,
                // and an absent report is indistinguishable from a lost one.
                out.append("\n  No actions were taken during this session.\n");
                return out.toString();
            }
            out.append("\n  ").append(actions.size()).append(" action(s), ")
               .append(rowsTouched).append(" row(s) touched:\n");
            for (AuditedAction action : actions) {
                out.append("    ").append(action.at()).append("  ")
                   .append(action.capability()).append("  ")
                   .append(action.targetId()).append("  (")
                   .append(action.rowCount()).append(" row(s))\n");
            }
            return out.toString();
        }
    }

    public static final class Builder {
        private String id;
        private String companyId;
        private String companyName;
        private String issuedByAccountId;
        private String engineerName;
        private String reason;
        private String ticketReference;
        private OffsetDateTime issuedAt;
        private Duration timeToLive = DEFAULT_TTL;
        private final Set<String> capabilities = new LinkedHashSet<String>();
        private String typedCompanyNameConfirmation;
        private String approvalDocumentId;
        private ApprovalState approvalState;
        private RepresentationMode mode;
        private List<String> approvingRepresentativeIds = new ArrayList<String>();
        private boolean issuanceDisabled;

        public Builder id(String value) {
            this.id = value;
            return this;
        }

        public Builder companyId(String value) {
            this.companyId = value;
            return this;
        }

        public Builder companyName(String value) {
            this.companyName = value;
            return this;
        }

        public Builder issuedByAccountId(String value) {
            this.issuedByAccountId = value;
            return this;
        }

        public Builder engineerName(String value) {
            this.engineerName = value;
            return this;
        }

        public Builder reason(String value) {
            this.reason = value;
            return this;
        }

        public Builder ticketReference(String value) {
            this.ticketReference = value;
            return this;
        }

        public Builder issuedAt(OffsetDateTime value) {
            this.issuedAt = value;
            return this;
        }

        public Builder timeToLive(Duration value) {
            this.timeToLive = value;
            return this;
        }

        /**
         * Ticks one capability.
         *
         * @throws IllegalArgumentException for a wildcard, or for anything on
         *         the never-grantable list
         */
        public Builder capability(String value) {
            if (Texts.isBlank(value)) {
                throw new IllegalArgumentException("a capability cannot be blank");
            }
            String trimmed = Texts.strip(value);
            if (trimmed.indexOf('*') >= 0) {
                throw new IllegalArgumentException(
                        "\"" + trimmed + "\" is a wildcard. Capabilities must be ticked "
                                + "individually so the issuer sees exactly what each one exposes; "
                                + "there is no grant-all.");
            }
            if (NEVER_GRANTABLE.contains(trimmed)) {
                throw new IllegalArgumentException(
                        "\"" + trimmed + "\" can never be granted to a temporary master. It would "
                                + "let the support session grant itself more access, make itself "
                                + "permanent, issue another session, or remove the record of what "
                                + "it did.");
            }
            capabilities.add(trimmed);
            return this;
        }

        public Builder typedCompanyNameConfirmation(String value) {
            this.typedCompanyNameConfirmation = value;
            return this;
        }

        public Builder approval(String documentId, ApprovalState state, RepresentationMode mode,
                List<String> approvingRepresentativeIds) {
            this.approvalDocumentId = documentId;
            this.approvalState = state;
            this.mode = mode;
            this.approvingRepresentativeIds = approvingRepresentativeIds == null
                    ? new ArrayList<String>()
                    : new ArrayList<String>(approvingRepresentativeIds);
            return this;
        }

        /** The installation-wide, permanent kill switch. */
        public Builder issuanceDisabledForInstallation(boolean value) {
            this.issuanceDisabled = value;
            return this;
        }

        public TemporaryMasterGrant build() {
            if (issuanceDisabled) {
                throw new IllegalStateException(
                        "temporary master issuance has been permanently disabled for this "
                                + "installation. It cannot be re-enabled from the application.");
            }
            if (Texts.isBlank(reason)) {
                throw new IllegalArgumentException(
                        "a temporary master account needs a reason. It appears in the audit trail "
                                + "and in the banner every user in the company sees.");
            }
            if (timeToLive == null || timeToLive.isZero() || timeToLive.isNegative()) {
                throw new IllegalArgumentException(
                        "a temporary master account needs a time to live; there is no open-ended "
                                + "grant. Default is " + DEFAULT_TTL.toHours() + " hours.");
            }
            if (timeToLive.compareTo(MAX_TTL) > 0) {
                throw new IllegalArgumentException(
                        "a temporary master account may last at most " + MAX_TTL.toHours()
                                + " hours. There is no extension: issue a new one, which means a "
                                + "new approval and a new reason.");
            }
            if (issuedAt == null) {
                throw new IllegalArgumentException("issuedAt is required to compute expiry");
            }
            if (Texts.isBlank(companyName)) {
                throw new IllegalArgumentException("companyName is required for the confirmation");
            }
            if (!companyName.equals(typedCompanyNameConfirmation)) {
                throw new IllegalArgumentException(
                        "the company name must be typed exactly to confirm issuance. Expected \""
                                + companyName + "\". This step is deliberately not a checkbox.");
            }
            if (Texts.isBlank(approvalDocumentId) || approvalState != ApprovalState.APPROVED) {
                throw new IllegalStateException(
                        "issuing a temporary master account requires representative approval under "
                                + "the company's representation mode. The approval document is "
                                + (approvalDocumentId == null ? "missing" : String.valueOf(approvalState))
                                + ".");
            }
            Set<String> distinct = new LinkedHashSet<String>(approvingRepresentativeIds);
            if (mode == null || !mode.isSatisfiedBy(distinct.size())) {
                throw new IllegalStateException(
                        "issuance was approved by " + distinct.size() + " representative(s) but "
                                + mode + " requires "
                                + (mode == null ? "an unknown number" : String.valueOf(mode.requiredApprovals()))
                                + ".");
            }
            return new TemporaryMasterGrant(this);
        }
    }
}
