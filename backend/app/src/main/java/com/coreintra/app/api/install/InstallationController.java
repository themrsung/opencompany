package com.coreintra.app.api.install;

import com.coreintra.app.api.error.ProblemDetail;
import com.coreintra.app.install.InstallationPlan;
import com.coreintra.app.install.InstallationGrants;
import com.coreintra.app.install.InstallationResult;
import com.coreintra.app.install.InstallationRow;
import com.coreintra.app.install.InstallationService;
import com.coreintra.auth.service.UserAccountService;
import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Immutables;
import com.coreintra.runtime.audit.AuditAction;
import com.coreintra.runtime.audit.AuditActorKind;
import com.coreintra.runtime.audit.AuditEvent;
import com.coreintra.runtime.audit.AuditLogService;
import com.coreintra.runtime.audit.AuditOutcome;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one door into an empty box, and it closes for good.
 *
 * <h2>The authentication exemption, stated plainly</h2>
 *
 * <p>This controller never asks for a caller. That is the whole exemption, it is
 * two methods wide, and there is no {@code CurrentPrincipal} field here to make
 * it accidentally wider. It is necessary rather than convenient: on an empty
 * installation there is no account to be, and every other endpoint in the
 * product refuses precisely because there is none.
 *
 * <p>What replaces authentication is the emptiness of the database.
 * {@link InstallationService} refuses unless there is no installation row, no
 * account and no company, and the installation row's primary key decides a race
 * between two callers. Once the box has been installed this endpoint answers
 * <b>410 Gone</b> and writes nothing, for ever — there is no flag, no
 * configuration property and no header that re-opens it — and on a database
 * that merely has something in it already, 409.
 *
 * <h2>The response is the only copy</h2>
 *
 * <p>{@code POST} returns the master's TOTP secret, its {@code otpauth} URI and
 * ten one-time recovery codes. The secret is stored encrypted, the codes only as
 * hashes, and neither is retrievable afterwards — the product has no passwords
 * and therefore no reset flow. A client who loses this response recovers from a
 * database backup.
 */
@RestController
@RequestMapping("/api/v1/install")
@Tag(name = "Installation",
        description = "Opening an empty installation. Available only while it is empty.")
public class InstallationController {

    /** The capability column on the audit row. There is no permission behind this one. */
    private static final String CAPABILITY = "admin.installation:open";

    private static final Logger LOG = LoggerFactory.getLogger(InstallationController.class);

    private final InstallationService installation;
    private final AuditLogService audit;
    private final ObjectMapper json;

    public InstallationController(InstallationService installation, AuditLogService audit,
            ObjectMapper json) {
        this.installation = installation;
        this.audit = audit;
        this.json = json;
    }

    /**
     * Whether this box still needs installing.
     *
     * <p>Unauthenticated like the POST, and deliberately uninformative when the
     * answer is no: a first-boot screen needs to know whether to show itself,
     * and nothing else here is anybody's business.
     */
    @GetMapping
    @SecurityRequirements
    @Operation(summary = "Is this installation still empty?",
            description = "True only while POST /api/v1/install would succeed.")
    public InstallationAvailabilityView availability() {
        return new InstallationAvailabilityView(installation.isAvailable());
    }

    /**
     * Creates the first company, the first master account and its bootstrap
     * grants, and returns the credentials once.
     *
     * <p>Audited after the transaction commits, not inside it:
     * {@code AuditLogService} writes each row in its own transaction so that the
     * record of a refusal survives the rollback of the thing it describes, and
     * {@code audit_log.company_id} is a foreign key — the company has to be
     * committed before the row that names it can be written.
     */
    @PostMapping
    @SecurityRequirements
    @Operation(summary = "Open an empty installation",
            description = "Unauthenticated by necessity and available only while the database "
                    + "holds no installation row, no account and no company. The TOTP secret and "
                    + "the recovery codes in the response are the only copies that will exist.")
    @ApiResponse(responseCode = "201", description = "Installed. The credentials are shown once.",
            content = @Content(schema = @Schema(implementation = InstallationResultView.class)))
    @ApiResponse(responseCode = "409",
            description = "The database is not empty, so this box is not ours to open.",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "410",
            description = "Already installed. The endpoint is gone and does not come back.",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<InstallationResultView> install(
            @Valid @RequestBody InstallationRequest body, HttpServletRequest request) {
        InstallationResult result = installation.install(planOf(body));

        audit.record(AuditEvent.builder()
                .companyId(result.companyId())
                // An installation has no actor. It is still an event, and it is
                // the first row in this log.
                .actor(null, AuditActorKind.ANONYMOUS, "설치 프로그램 (installer)")
                .capability(CAPABILITY)
                .target("installation", InstallationRow.ID)
                .action(AuditAction.CREATE)
                .outcome(AuditOutcome.ALLOWED)
                .rowsTouched(1)
                // What was created, and deliberately not what it was created
                // with: the credential belongs in the response and nowhere
                // else, least of all in a row kept for years.
                .after(describe(Immutables.mapOf(
                        "companyCode", result.companyCode(),
                        "masterUsername", result.masterUsername())))
                .request(null, addressOf(request), request.getHeader("User-Agent"))
                .occurredAt(now())
                .build());

        return ResponseEntity.status(HttpStatus.CREATED).body(new InstallationResultView(result));
    }

    /**
     * The installation is open, so this endpoint is not.
     *
     * <p>410 Gone, and the choice is deliberate. 403 would say "not you", which
     * invites the reader to look for a credential that would work; there is
     * none, and there never will be. 404 would say "wrong URL", which sends a
     * confused operator looking for the right one. 410 says what is true: this
     * endpoint existed, it has been used, and it is not coming back. Nothing
     * about that is a secret — anyone can discover it by asking — so the detail
     * says what to do instead.
     */
    @ExceptionHandler(InstallationService.AlreadyInstalledException.class)
    public ResponseEntity<ProblemDetail> onAlreadyInstalled(
            InstallationService.AlreadyInstalledException refusal) {
        recordRefusal("already_installed");
        return gone(refusal.getMessage());
    }

    /**
     * Something is here that this installer did not put here. Refused, loudly.
     *
     * <p>{@code UserAccountService} and {@code InstallationGrants} refuse on
     * their own account as well as being asked politely first, and their
     * refusals arrive here rather than as a 500: on a box that is not empty they
     * are the right answer, not a fault.
     */
    @ExceptionHandler({InstallationService.NotEmptyException.class,
            UserAccountService.InstallationNotEmptyException.class,
            InstallationGrants.GrantsAlreadyExistException.class})
    public ResponseEntity<ProblemDetail> onNotEmpty(IllegalStateException refusal) {
        recordRefusal("installation_not_empty");
        // 409 rather than 410 here: the endpoint has not been used, and what
        // stands in the way is the state of the database rather than the fact
        // of a previous installation.
        return problem(HttpStatus.CONFLICT, "installation_not_empty",
                "This database is not empty", refusal.getMessage());
    }

    /**
     * Two installers arrived at once and this one lost.
     *
     * <p>The counts both requests read said zero; the primary key on
     * {@code installation} is what decided between them, and everything this
     * transaction wrote has been rolled back. The honest answer to the loser is
     * the same as to anyone else arriving late.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> onRaceLost(DataIntegrityViolationException collision) {
        return gone("설치가 동시에 진행되어 이 요청은 취소되었습니다. 이미 완료된 설치를 사용해 "
                + "주십시오. (Another installation completed while this request was in flight, so "
                + "this one was rolled back in its entirety. The box is installed; sign in with "
                + "the credentials that request returned.)");
    }

    /** The endpoint has been used and is not coming back. */
    private static ResponseEntity<ProblemDetail> gone(String detail) {
        return problem(HttpStatus.GONE, "already_installed", "Already installed", detail);
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code,
            String title, String detail) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemDetail.of(status.value(), code, title, detail));
    }

    /**
     * Notes an attempt on a box that is not installable, in the log rather than
     * in the trail.
     *
     * <p>Somebody probing the installer of a live system is worth seeing, and
     * the first instinct is to audit it. That instinct is wrong here: this
     * endpoint is unauthenticated and unthrottled — the rate limiter is keyed on
     * an account, and there is none — so an audit row per refusal is an
     * unauthenticated stranger writing unbounded rows into a table that by
     * design can never be pruned or deleted. A log line is rotated, aggregated
     * and alerted on; the audit log is evidence, and evidence anybody can
     * generate at will stops being evidence.
     */
    private void recordRefusal(String code) {
        Optional<InstallationRow> installed = installation.installed();
        LOG.warn("설치 요청이 거부되었습니다 ({}). (An installation attempt was refused on an "
                + "installation that is {}.)", code,
                installed.isPresent() ? "already open" : "not empty");
    }

    /**
     * The {@code after} column, rendered by the same Jackson the API uses.
     *
     * <p>Hand-built JSON would be shorter and would produce an unparseable audit
     * row the first time a company code contained a quotation mark.
     */
    private String describe(Map<String, String> fields) {
        try {
            return json.writeValueAsString(fields);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("a map of strings failed to serialise", impossible);
        }
    }

    private static BusinessInstant now() {
        LocalTime time = LocalTime.now();
        return BusinessInstant.of(LocalDate.now(), time.getHour(), time.getMinute(),
                time.getSecond());
    }

    /**
     * The caller's address.
     *
     * <p>{@code X-Forwarded-For} is trusted for the same reason
     * {@code SessionController} trusts it: every deployment of this product sits
     * behind its own reverse proxy on the same box.
     */
    private static String addressOf(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.trim().isEmpty()) {
            int comma = forwarded.indexOf(',');
            return comma > 0 ? forwarded.substring(0, comma).trim() : forwarded.trim();
        }
        return request.getRemoteAddr();
    }

    private static InstallationPlan planOf(InstallationRequest body) {
        return InstallationPlan.builder()
                .company(body.getCompanyCode(), body.getCompanyNameKo(), body.getCompanyNameEn())
                .registration(body.getBusinessRegistrationNumber(), body.getBaseCurrencyCode(),
                        body.getEstablishedOn())
                .master(body.getMasterUsername(), body.getMasterDisplayName())
                .representation(body.getRepresentationMode(), body.getRequiredApprovals(),
                        body.getDesignatedRepresentatives())
                .build();
    }
}
