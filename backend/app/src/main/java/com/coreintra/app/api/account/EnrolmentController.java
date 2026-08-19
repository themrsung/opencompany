package com.coreintra.app.api.account;

import com.coreintra.app.api.permission.CurrentPrincipal;
import com.coreintra.auth.service.AuthenticationService;
import com.coreintra.compat.Immutables;
import com.coreintra.core.org.UserAccount;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.service.OrgPermissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * TOTP enrolment and recovery codes.
 *
 * <p>There is no password anywhere in this class, and no reset flow to go with
 * it (§7). Losing a device is recovered with a one-time recovery code, or by an
 * administrator who holds {@code admin.account:update} over the person starting
 * a fresh enrolment for them — which is an action the audit trail records, not a
 * quiet reset.
 *
 * <h2>Shown once</h2>
 *
 * <p>{@code POST /account/enrolment} is the only response in this API that
 * contains the otpauth URI and the recovery codes, and it is the only one that
 * ever will: the secret is encrypted at rest and the codes are stored hashed, so
 * nothing here could return them a second time even if a later endpoint asked.
 * A client that discards this response has to enrol again, which is the correct
 * outcome — the alternative is an endpoint that hands out working credentials to
 * whoever calls it twice.
 *
 * <p>Asking again is therefore destructive in the way that matters: beginning a
 * new enrolment replaces the secret and issues a new set of codes, invalidating
 * the old ones. That is deliberate for the lost-device case and is stated in the
 * operation description so no client discovers it by accident.
 */
@RestController
@RequestMapping("/api/v1/account/enrolment")
@Tag(name = "Account — enrolment",
        description = "TOTP enrolment and one-time recovery codes. No passwords exist in this "
                + "system, so this is the whole of credential management.")
public class EnrolmentController {

    private final AuthenticationService authentication;
    private final AccountSubjects subjects;
    private final CurrentPrincipal currentPrincipal;

    public EnrolmentController(AuthenticationService authentication, AccountSubjects subjects,
            CurrentPrincipal currentPrincipal) {
        this.authentication = authentication;
        this.subjects = subjects;
        this.currentPrincipal = currentPrincipal;
    }

    @GetMapping
    @Operation(summary = "Enrolment status",
            description = "How many recovery codes are left, and whether email OTP is available "
                    + "on this installation. Never returns a secret.")
    public EnrolmentStatus status(
            @RequestParam(name = "accountId", required = false) String accountId,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = businessDate == null ? LocalDate.now() : businessDate;
        UserAccount subject = subjects.authorise(caller, subjectId(caller, accountId),
                OrgPermissions.ACCOUNT_READ, on, "enrolment status");

        return new EnrolmentStatus(subject.id(),
                authentication.remainingRecoveryCodes(subject.id()),
                authentication.isEmailOtpAvailable());
    }

    @PostMapping
    @Operation(summary = "Begin enrolment — returns the otpauth URI and the recovery codes, once",
            description = "Replaces any existing secret and invalidates any codes already "
                    + "issued, so calling it for an enrolled account is how a lost device is "
                    + "recovered and is not a safe retry. The codes are stored hashed and cannot "
                    + "be read back afterwards. Not usable for sign-in until confirmed.")
    public Enrolled begin(
            @RequestParam(name = "accountId", required = false) String accountId,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = businessDate == null ? LocalDate.now() : businessDate;
        UserAccount subject = subjects.authorise(caller, subjectId(caller, accountId),
                OrgPermissions.ACCOUNT_UPDATE, on, "begin TOTP enrolment");

        AuthenticationService.Enrolment enrolment = authentication.beginEnrolment(subject.id());
        return new Enrolled(subject.id(), enrolment.otpauthUri(), enrolment.secretBase32(),
                enrolment.recoveryCodes());
    }

    @PostMapping("/confirmation")
    @Operation(summary = "Confirm enrolment by proving possession",
            description = "Until this succeeds the factor cannot be used to sign in — otherwise "
                    + "somebody who scanned the QR and closed the tab would hold a credential "
                    + "they never verified. A wrong code answers confirmed=false rather than an "
                    + "error: it is an ordinary outcome of typing.")
    public Confirmation confirm(
            @RequestParam(name = "accountId", required = false) String accountId,
            @Valid @RequestBody ConfirmationRequest body,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = businessDate == null ? LocalDate.now() : businessDate;
        UserAccount subject = subjects.authorise(caller, subjectId(caller, accountId),
                OrgPermissions.ACCOUNT_UPDATE, on, "confirm TOTP enrolment");

        boolean confirmed = authentication.confirmEnrolment(subject.id(), body.getCode());
        return new Confirmation(confirmed, authentication.remainingRecoveryCodes(subject.id()));
    }

    @PostMapping("/recovery-codes")
    @Operation(summary = "Issue a fresh set of recovery codes — returned once",
            description = "Every previous code stops working immediately. Returned in this "
                    + "response and never again: they are stored hashed and single-use.")
    public RecoveryCodes regenerate(
            @RequestParam(name = "accountId", required = false) String accountId,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = businessDate == null ? LocalDate.now() : businessDate;
        UserAccount subject = subjects.authorise(caller, subjectId(caller, accountId),
                OrgPermissions.ACCOUNT_UPDATE, on, "regenerate recovery codes");

        return new RecoveryCodes(subject.id(), authentication.regenerateRecoveryCodes(subject.id()));
    }

    /**
     * The account being acted on: the caller's own unless one is named.
     *
     * <p>Defaulting to the caller does not skip a check — the evaluator still
     * decides, against a target carrying that account's own employee, so
     * managing your own credentials needs {@code admin.account:update} at
     * {@code SELF} and nothing here is implicitly permitted.
     */
    private static String subjectId(PermissionPrincipal caller, String requested) {
        return requested == null ? caller.accountId() : requested;
    }

    /** What sign-in needs, and what is never returned twice. */
    public static class Enrolled {
        private final String accountId;
        private final String otpauthUri;
        private final String secretBase32;
        private final List<String> recoveryCodes;

        Enrolled(String accountId, String otpauthUri, String secretBase32,
                List<String> recoveryCodes) {
            this.accountId = accountId;
            this.otpauthUri = otpauthUri;
            this.secretBase32 = secretBase32;
            this.recoveryCodes = Immutables.copyOf(recoveryCodes);
        }

        public String getAccountId() {
            return accountId;
        }

        /** Render as a QR code. Contains the shared secret; do not log it. */
        public String getOtpauthUri() {
            return otpauthUri;
        }

        /** The same secret, grouped for typing in by hand when a camera will not do. */
        public String getSecretBase32() {
            return secretBase32;
        }

        /** Single-use, hashed at rest. Show once, tell the user to keep them. */
        public List<String> getRecoveryCodes() {
            return recoveryCodes;
        }
    }

    /** Nothing secret: safe to poll. */
    public static class EnrolmentStatus {
        private final String accountId;
        private final long remainingRecoveryCodes;
        private final boolean emailOtpAvailable;

        EnrolmentStatus(String accountId, long remainingRecoveryCodes, boolean emailOtpAvailable) {
            this.accountId = accountId;
            this.remainingRecoveryCodes = remainingRecoveryCodes;
            this.emailOtpAvailable = emailOtpAvailable;
        }

        public String getAccountId() {
            return accountId;
        }

        /** Shown in the UI so nobody discovers they are out of codes at the worst moment. */
        public long getRemainingRecoveryCodes() {
            return remainingRecoveryCodes;
        }

        /** False when the installation has configured no mail sender. Hidden, not broken. */
        public boolean isEmailOtpAvailable() {
            return emailOtpAvailable;
        }
    }

    /** The result of proving possession. */
    public static class Confirmation {
        private final boolean confirmed;
        private final long remainingRecoveryCodes;

        Confirmation(boolean confirmed, long remainingRecoveryCodes) {
            this.confirmed = confirmed;
            this.remainingRecoveryCodes = remainingRecoveryCodes;
        }

        public boolean isConfirmed() {
            return confirmed;
        }

        public long getRemainingRecoveryCodes() {
            return remainingRecoveryCodes;
        }
    }

    /** A fresh set, returned once. */
    public static class RecoveryCodes {
        private final String accountId;
        private final List<String> recoveryCodes;

        RecoveryCodes(String accountId, List<String> recoveryCodes) {
            this.accountId = accountId;
            this.recoveryCodes = Immutables.copyOf(recoveryCodes);
        }

        public String getAccountId() {
            return accountId;
        }

        public List<String> getRecoveryCodes() {
            return recoveryCodes;
        }
    }

    /** The six digits from the authenticator. */
    public static class ConfirmationRequest {
        @NotBlank
        private String code;

        public String getCode() {
            return code;
        }

        public void setCode(String value) {
            this.code = value;
        }
    }
}
