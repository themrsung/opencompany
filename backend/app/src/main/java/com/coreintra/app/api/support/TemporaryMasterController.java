package com.coreintra.app.api.support;

import com.coreintra.app.api.permission.CurrentPrincipal;
import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Immutables;
import com.coreintra.core.permission.PermissionEvaluator;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionTarget;
import com.coreintra.runtime.support.TemporaryMasterCapabilities;
import com.coreintra.runtime.support.TemporaryMasterGrantRow;
import com.coreintra.runtime.support.TemporaryMasterIssuance;
import com.coreintra.runtime.support.TemporaryMasterService;
import com.coreintra.runtime.support.TemporaryMasterSessionReport;
import com.coreintra.runtime.support.TemporaryMasterSessionReporter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issuing, revoking and reporting on a vendor support session — §8, the
 * highest-risk feature in the system.
 *
 * <p>The invariants live in {@code TemporaryMasterService} and in the schema,
 * not here; this class deliberately adds only what belongs at the edge:
 *
 * <ul>
 *   <li>the caller must be a master and hold the issue permission;</li>
 *   <li>the request must carry the company name as typed by a human, which is
 *       the last step of the confirmation and the reason it is not a toggle;</li>
 *   <li><b>every ticked capability must have plain-language wording</b>, because
 *       a 대표이사 cannot consent to a permission string. Issuing with an
 *       undescribed capability is refused, which means adding a capability
 *       forces someone to write down what it exposes.</li>
 * </ul>
 *
 * <p>There is no endpoint to extend a session and there will not be one. §8 says
 * no extension: issue a new one. An extension endpoint would turn the hard
 * 24-hour ceiling into a suggestion, one renewal at a time.
 *
 * <p>There is also no "grant all". The request takes a list of capabilities and
 * nothing else — no flag, no wildcard, no empty-means-everything. An empty list
 * issues a session that can read nothing, which is a legitimate and useful
 * starting point, and is a named acceptance test.
 */
@RestController
@RequestMapping("/api/v1/support/temporary-master")
@Tag(name = "Temporary master",
        description = "Vendor support access: individually ticked capabilities, a hard time "
                + "limit, no extension, and a banner the whole company can see.")
public class TemporaryMasterController {

    private static final PermissionKey ISSUE = PermissionKey.parse("admin.temporaryMaster:issue");
    private static final PermissionKey REVOKE = PermissionKey.parse("support.temporary_master:revoke");
    private static final PermissionKey READ_REPORT =
            PermissionKey.parse("support.temporary_master:read");

    /** §8: default four hours. The ceiling is the service's and the schema's business. */
    private static final Duration DEFAULT_TTL = Duration.ofHours(4);

    private final TemporaryMasterService sessions;
    private final TemporaryMasterSessionReporter reporter;
    private final PermissionEvaluator evaluator;
    private final CurrentPrincipal current;

    public TemporaryMasterController(TemporaryMasterService sessions,
            TemporaryMasterSessionReporter reporter, PermissionEvaluator evaluator,
            CurrentPrincipal current) {
        this.sessions = sessions;
        this.reporter = reporter;
        this.evaluator = evaluator;
        this.current = current;
    }

    /** One capability, with what it exposes, for the tick list. */
    public static class Capability {
        private final String key;
        private final String description;

        Capability(String key, String description) {
            this.key = key;
            this.description = description;
        }

        public String getKey() {
            return key;
        }

        /** Korean and English, in end-user terms. Never a permission string alone. */
        public String getDescription() {
            return description;
        }
    }

    public static class IssueRequest {
        @NotBlank
        private String companyId;
        @NotBlank
        private String companyName;
        /** The account the engineer will sign in as. Created and disabled separately. */
        @NotBlank
        private String accountId;
        @NotBlank
        private String engineerName;
        @NotBlank
        @Size(min = 10, message = "Say why this session is needed, in a sentence someone could "
                + "read back to you in three months.")
        private String reason;
        private String ticketReference;
        /** Typed by the issuer at the final confirmation step. Must equal companyName. */
        @NotBlank
        private String typedCompanyName;
        private Integer hours;
        /** Every one ticked individually. There is no flag that means "all". */
        private List<String> capabilities;
        /** The 대표 approval this was issued under. */
        @NotBlank
        private String approvalDocumentId;
        @NotBlank
        private String representationMode;
        private int requiredApprovals;
        @NotEmpty(message = "A support session needs the representative approval that authorised it.")
        private List<Approver> approvedBy;

        public String getCompanyId() {
            return companyId;
        }

        public void setCompanyId(String value) {
            this.companyId = value;
        }

        public String getCompanyName() {
            return companyName;
        }

        public void setCompanyName(String value) {
            this.companyName = value;
        }

        public String getAccountId() {
            return accountId;
        }

        public void setAccountId(String value) {
            this.accountId = value;
        }

        public String getEngineerName() {
            return engineerName;
        }

        public void setEngineerName(String value) {
            this.engineerName = value;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String value) {
            this.reason = value;
        }

        public String getTicketReference() {
            return ticketReference;
        }

        public void setTicketReference(String value) {
            this.ticketReference = value;
        }

        public String getTypedCompanyName() {
            return typedCompanyName;
        }

        public void setTypedCompanyName(String value) {
            this.typedCompanyName = value;
        }

        public Integer getHours() {
            return hours;
        }

        public void setHours(Integer value) {
            this.hours = value;
        }

        public List<String> getCapabilities() {
            return capabilities;
        }

        public void setCapabilities(List<String> value) {
            this.capabilities = value;
        }

        public String getApprovalDocumentId() {
            return approvalDocumentId;
        }

        public void setApprovalDocumentId(String value) {
            this.approvalDocumentId = value;
        }

        public String getRepresentationMode() {
            return representationMode;
        }

        public void setRepresentationMode(String value) {
            this.representationMode = value;
        }

        public int getRequiredApprovals() {
            return requiredApprovals;
        }

        public void setRequiredApprovals(int value) {
            this.requiredApprovals = value;
        }

        public List<Approver> getApprovedBy() {
            return approvedBy;
        }

        public void setApprovedBy(List<Approver> value) {
            this.approvedBy = value;
        }
    }

    public static class Approver {
        @NotBlank
        private String accountId;
        @NotBlank
        private String name;

        public String getAccountId() {
            return accountId;
        }

        public void setAccountId(String value) {
            this.accountId = value;
        }

        public String getName() {
            return name;
        }

        public void setName(String value) {
            this.name = value;
        }
    }

    public static class IssuedSession {
        private final String grantId;
        private final String expiresAt;
        private final List<String> capabilities;

        IssuedSession(TemporaryMasterGrantRow grant, List<String> capabilities) {
            this.grantId = grant.id();
            this.expiresAt = String.valueOf(grant.expiresAt());
            this.capabilities = Immutables.copyOf(capabilities);
        }

        public String getGrantId() {
            return grantId;
        }

        public String getExpiresAt() {
            return expiresAt;
        }

        public List<String> getCapabilities() {
            return capabilities;
        }
    }

    @GetMapping("/capabilities")
    @Operation(summary = "The capabilities that can be ticked, and what each exposes",
            description = "The description is what the issuer and the approving 대표 read. A "
                    + "capability with no description cannot be issued.")
    public List<Capability> capabilities() {
        current.require();
        List<Capability> catalogue = new ArrayList<Capability>();
        for (String key : SupportCapabilityWording.keys()) {
            catalogue.add(new Capability(key, SupportCapabilityWording.describe(key)));
        }
        return Immutables.copyOf(catalogue);
    }

    @PostMapping
    @Operation(summary = "Issue a support session",
            description = "Requires a master account, the issue permission, representative "
                    + "approval under the company's mode, and the company name typed by hand.")
    public ResponseEntity<IssuedSession> issue(@Valid @RequestBody IssueRequest body) {
        PermissionPrincipal principal = current.require();
        LocalDate today = LocalDate.now();

        evaluator.check(principal, ISSUE, PermissionTarget.builder()
                .companyId(body.getCompanyId())
                .asOfBusinessDate(today)
                .description("temporary master issuance")
                .build()).orThrow();

        if (!principal.isMaster()) {
            // The service checks the approval evidence; this checks who is
            // asking. Both, because a master without the permission and a
            // permission-holder who is not a master are different mistakes.
            throw new IllegalArgumentException(
                    "Only a master account may issue a support session.");
        }
        if (sessions.issuanceDisabled()) {
            throw new IllegalArgumentException(
                    "Temporary master issuance has been permanently disabled for this "
                            + "installation. It cannot be re-enabled.");
        }
        if (!body.getTypedCompanyName().trim().equals(body.getCompanyName().trim())) {
            throw new IllegalArgumentException(
                    "The company name you typed does not match. Type \"" + body.getCompanyName()
                            + "\" exactly to confirm.");
        }

        Set<String> capabilities = new LinkedHashSet<String>();
        if (body.getCapabilities() != null) {
            for (String capability : body.getCapabilities()) {
                capabilities.add(TemporaryMasterCapabilities.validate(capability));
            }
        }
        if (!SupportCapabilityWording.fullyDescribed(capabilities)) {
            throw new IllegalArgumentException(
                    "One of the selected capabilities has no plain-language description, so the "
                            + "issuance screen could not have said what it exposes. Register its "
                            + "wording before it can be granted.");
        }

        OffsetDateTime now = OffsetDateTime.now();
        TemporaryMasterIssuance.Builder issuance = TemporaryMasterIssuance.builder()
                .company(body.getCompanyId(), body.getCompanyName())
                .accountId(body.getAccountId())
                .issuedByAccountId(principal.accountId())
                .engineerName(body.getEngineerName())
                .reason(body.getReason())
                .ticketReference(body.getTicketReference())
                // Trimmed on the way through. The service checks this again and
                // does not trim, and it is right not to; but a trailing space
                // from a paste is not a failed confirmation, it is a space.
                .typedCompanyName(body.getTypedCompanyName().trim())
                .issuedAt(now)
                .issuedOn(BusinessInstant.of(today, secondsIntoDay(now)))
                .timeToLive(body.getHours() == null
                        ? DEFAULT_TTL : Duration.ofHours(body.getHours().longValue()))
                .approval(body.getApprovalDocumentId(), body.getRepresentationMode(),
                        body.getRequiredApprovals());

        for (String capability : capabilities) {
            issuance.capability(capability);
        }
        for (Approver approver : body.getApprovedBy()) {
            issuance.approvedBy(approver.getAccountId(), approver.getName());
        }

        TemporaryMasterGrantRow grant = sessions.issue(issuance.build());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new IssuedSession(grant, SupportCapabilityWording.describe(grant)));
    }

    @PostMapping("/{grantId}/revoke")
    @Operation(summary = "End a live session immediately",
            description = "Any master may do this. The session's tokens stop working on the very "
                    + "next request, which is why they are opaque rather than signed.")
    public ResponseEntity<Void> revoke(@PathVariable String grantId) {
        PermissionPrincipal principal = current.require();
        TemporaryMasterGrantRow grant = requireGrant(grantId);

        evaluator.check(principal, REVOKE, PermissionTarget.builder()
                .companyId(grant.companyId())
                .asOfBusinessDate(LocalDate.now())
                .description("live support session")
                .build()).orThrow();

        sessions.revoke(grantId, principal.accountId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{grantId}/report")
    @Operation(summary = "The session report",
            description = "Every action taken during the session. Generated on expiry or "
                    + "revocation and delivered to all masters.")
    public TemporaryMasterSessionReport report(@PathVariable String grantId) {
        PermissionPrincipal principal = current.require();
        TemporaryMasterGrantRow grant = requireGrant(grantId);

        evaluator.check(principal, READ_REPORT, PermissionTarget.builder()
                .companyId(grant.companyId())
                .asOfBusinessDate(LocalDate.now())
                .description("support session report")
                .build()).orThrow();

        return reporter.reportFor(grantId);
    }

    @PostMapping("/kill-switch")
    @Operation(summary = "Disable support-session issuance permanently",
            description = "Permanent means permanent: the database refuses to set it back. "
                    + "There is no endpoint to undo this and no configuration flag that "
                    + "overrides it.")
    public ResponseEntity<Void> disableForever(@RequestParam String companyId,
            @RequestParam String reason) {
        PermissionPrincipal principal = current.require();
        evaluator.check(principal, ISSUE, PermissionTarget.builder()
                .companyId(companyId)
                .asOfBusinessDate(LocalDate.now())
                .description("temporary master kill switch")
                .build()).orThrow();
        if (!principal.isMaster()) {
            throw new IllegalArgumentException("Only a master account may use the kill switch.");
        }
        sessions.disableIssuanceForInstallation(principal.accountId(), reason);
        return ResponseEntity.noContent().build();
    }

    /**
     * Looks a session up by grant id.
     *
     * <p>The service indexes by the support account rather than by grant id,
     * because that is the question the request path asks a thousand times a
     * minute. Revoking and reporting ask it the other way round and are rare,
     * so they pay for the scan over the company's live sessions rather than the
     * hot path paying for a second index.
     */
    private TemporaryMasterGrantRow requireGrant(String grantId) {
        TemporaryMasterGrantRow grant = sessions.grantById(grantId);
        if (grant == null) {
            throw new IllegalArgumentException("No such support session: " + grantId);
        }
        return grant;
    }

    private static int secondsIntoDay(OffsetDateTime moment) {
        return moment.toLocalTime().toSecondOfDay();
    }
}
