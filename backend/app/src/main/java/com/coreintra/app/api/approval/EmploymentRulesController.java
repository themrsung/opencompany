package com.coreintra.app.api.approval;

import com.coreintra.app.api.permission.CurrentPrincipal;
import com.coreintra.approval.rules.EmploymentRules;
import com.coreintra.approval.service.EmploymentRulesService;
import com.coreintra.compat.Immutables;
import com.coreintra.core.permission.PermissionPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
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
 * 취업규칙 — versions, the section-level diff, and acknowledgement receipts.
 *
 * <h2>The invariant this endpoint exists to not break</h2>
 *
 * <p>§4: any create, amend or repeal requires 대표자 결재 under the company's
 * representation mode. No admin, no master account and <b>no API path</b> may
 * bypass it. That is enforced in {@code EmploymentRules.publish}, which refuses
 * without an approval document that is APPROVED, of the right type, and signed
 * by enough distinct representatives for the mode in force — so this controller
 * cannot enact anything, only carry an enactment that already happened.
 *
 * <p>{@code hr.rules:write} authorises <em>proposing</em> a change and nothing
 * more. The two are separate on purpose: an HR manager may draft an amendment,
 * and whether it takes effect is not theirs to decide.
 *
 * <h2>Acknowledgements are per person per version</h2>
 *
 * <p>A receipt against the 2024 rules says nothing about the 2026 ones, and one
 * that carried over would be worthless as evidence exactly when it was needed.
 * Nobody can record one on somebody else's behalf; the service refuses it, and
 * so a manager cannot tick their team off as having read anything.
 */
@RestController
@RequestMapping("/api/v1/employment-rules")
@Tag(name = "취업규칙",
        description = "Versioned employment rules. Every change needs 대표자 결재 under the "
                + "company's representation mode; there is no administrative override and a "
                + "master account does not have one either.")
public class EmploymentRulesController {

    private final EmploymentRulesService rules;
    private final CurrentPrincipal currentPrincipal;

    public EmploymentRulesController(EmploymentRulesService rules,
            CurrentPrincipal currentPrincipal) {
        this.rules = rules;
        this.currentPrincipal = currentPrincipal;
    }

    @GetMapping
    @Operation(summary = "The version in force on a date",
            description = "What an employee is actually bound by. Defaults to today. A past "
                    + "date returns the version that was effective then, not the newest one — "
                    + "a dispute about last March is settled by March's rules. 404 when the "
                    + "company has no rules in force on that date.")
    public ResponseEntity<EmploymentRulesView> effectiveOn(
            @RequestParam(name = "companyId") String companyId,
            @RequestParam(name = "on", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate on) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate date = ApiWire.onDate(on);
        Optional<EmploymentRules> found = rules.effectiveOn(caller, companyId, date);
        if (!found.isPresent()) {
            throw new ApiNotFoundException("이 날짜에 시행 중인 취업규칙이 없습니다. (Company "
                    + companyId + " has no 취업규칙 in force on " + date + ".)");
        }
        EmploymentRules effective = found.get();
        return ResponseEntity.ok()
                .eTag(EmploymentRulesView.tagOf(effective))
                .body(EmploymentRulesView.from(effective));
    }

    @GetMapping("/{version}/diff")
    @Operation(summary = "Section-level diff against the previous version",
            description = "Article by article — ADDED, REMOVED, AMENDED, UNCHANGED — because "
                    + "that is what a 대표이사 approving an amendment needs to read. Empty for "
                    + "the first version: rendering every article as ADDED would drown the "
                    + "reader in a change list on the one occasion nothing has changed.")
    public List<SectionDiffView> diff(
            @PathVariable int version,
            @RequestParam(name = "companyId") String companyId) {

        PermissionPrincipal caller = currentPrincipal.require();
        List<SectionDiffView> views = new ArrayList<SectionDiffView>();
        for (EmploymentRules.SectionDiff diff
                : rules.diffAgainstPrevious(caller, companyId, version)) {
            views.add(SectionDiffView.from(diff));
        }
        return views;
    }

    @PostMapping("/proposals")
    @Operation(summary = "Propose a change, as a 결재 document",
            description = "Creates the approval document that a change must travel through. "
                    + "Requires hr.rules:write, which authorises proposing and nothing else. "
                    + "Submit it through /api/v1/approvals/{id}/submit and have the "
                    + "representatives approve it; only then can it enact a version.")
    public ResponseEntity<ApprovalDocumentSummary> propose(
            @Valid @RequestBody ProposeRulesChangeRequest body) {

        PermissionPrincipal caller = currentPrincipal.require();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApprovalDocumentSummary.from(rules.proposeChange(caller,
                        body.getCompanyId(), body.getTitle(),
                        ApiWire.onDate(body.getBusinessDate()))));
    }

    @PostMapping
    @Operation(summary = "Enact a version that 대표자 결재 has approved",
            description = "Refused with 403 unless approvalDocumentId names an APPROVED "
                    + "EMPLOYMENT_RULES document signed by enough distinct representatives for "
                    + "the representation mode in force — all of them under 공동대표, any one "
                    + "under 각자대표. This refusal is a domain invariant: no permission grant "
                    + "reaches past it, and neither does a master account. Omitting the "
                    + "approval document is refused for the same reason as naming an "
                    + "unapproved one.")
    public ResponseEntity<EmploymentRulesView> publish(
            @Valid @RequestBody PublishRulesRequest body) {

        PermissionPrincipal caller = currentPrincipal.require();
        List<EmploymentRules.Section> sections = new ArrayList<EmploymentRules.Section>();
        for (SectionRequest section : body.getSections()) {
            sections.add(new EmploymentRules.Section(section.getNumber(),
                    section.getHeadingKo(), section.getHeadingEn(),
                    section.getBodyKo(), section.getBodyEn()));
        }
        EmploymentRules published = rules.publish(caller, body.getCompanyId(),
                body.getEffectiveFrom(), sections, body.getApprovalDocumentId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(EmploymentRulesView.tagOf(published))
                .body(EmploymentRulesView.from(published));
    }

    @PostMapping("/{rulesId}/acknowledgements")
    @Operation(summary = "Record that you have read this version",
            description = "A personal statement, so only for yourself: recording one on "
                    + "somebody else's behalf is refused. Idempotent by nature — a second "
                    + "acknowledgement of the same version on the same date is the same fact.")
    public ResponseEntity<Void> acknowledge(
            @PathVariable String rulesId,
            @Valid @RequestBody AcknowledgeRequest body) {

        PermissionPrincipal caller = currentPrincipal.require();
        rules.acknowledge(caller, rulesId, body.getEmployeeId(),
                ApiWire.onDate(body.getAcknowledgedOn()));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{rulesId}/acknowledgements")
    @Operation(summary = "Who has acknowledged this version",
            description = "The HR follow-up list: employee ids, so it can be joined against "
                    + "the roster to find who has not.")
    public AcknowledgementsView acknowledgedBy(
            @PathVariable String rulesId,
            @RequestParam(name = "companyId") String companyId) {

        PermissionPrincipal caller = currentPrincipal.require();
        return new AcknowledgementsView(rulesId, rules.acknowledgedBy(caller, companyId, rulesId));
    }

    /** Who has signed off on a version. */
    @Schema(name = "EmploymentRulesAcknowledgements")
    public static class AcknowledgementsView {
        private final String rulesId;
        private final List<String> employeeIds;

        AcknowledgementsView(String rulesId, List<String> employeeIds) {
            this.rulesId = rulesId;
            this.employeeIds = Immutables.copyOf(employeeIds);
        }

        public String getRulesId() {
            return rulesId;
        }

        public List<String> getEmployeeIds() {
            return employeeIds;
        }

        public int getCount() {
            return employeeIds.size();
        }
    }

    /** A proposal to change the rules. */
    @Schema(name = "ProposeRulesChangeRequest")
    public static class ProposeRulesChangeRequest {

        @NotBlank
        private String companyId;

        @NotBlank(message = "제목을 입력해 주십시오. (The proposal needs a title: it is what the "
                + "representatives see in their inbox.)")
        @Size(max = 200)
        private String title;

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate businessDate;

        public String getCompanyId() {
            return companyId;
        }

        public void setCompanyId(String value) {
            this.companyId = value;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String value) {
            this.title = value;
        }

        public LocalDate getBusinessDate() {
            return businessDate;
        }

        public void setBusinessDate(LocalDate value) {
            this.businessDate = value;
        }
    }

    /** An enactment. */
    @Schema(name = "PublishEmploymentRulesRequest")
    public static class PublishRulesRequest {

        @NotBlank
        private String companyId;

        @NotNull(message = "시행일을 입력해 주십시오. (A version needs the date it takes effect.)")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate effectiveFrom;

        @NotEmpty(message = "본문이 없는 취업규칙은 시행할 수 없습니다. (Rules with no articles "
                + "cannot be enacted.)")
        @Valid
        private List<SectionRequest> sections;

        private String approvalDocumentId;

        public String getCompanyId() {
            return companyId;
        }

        public void setCompanyId(String value) {
            this.companyId = value;
        }

        public LocalDate getEffectiveFrom() {
            return effectiveFrom;
        }

        public void setEffectiveFrom(LocalDate value) {
            this.effectiveFrom = value;
        }

        public List<SectionRequest> getSections() {
            return sections;
        }

        public void setSections(List<SectionRequest> value) {
            this.sections = value;
        }

        @Schema(description = "The APPROVED EMPLOYMENT_RULES document that authorises this "
                + "change. Not optional in practice: omitting it is refused with 403, the "
                + "same as naming a document the representatives have not signed.")
        public String getApprovalDocumentId() {
            return approvalDocumentId;
        }

        public void setApprovalDocumentId(String value) {
            this.approvalDocumentId = value;
        }
    }

    /** One 조. */
    @Schema(name = "EmploymentRulesSectionRequest")
    public static class SectionRequest {

        @NotBlank(message = "조 번호가 필요합니다. (A section needs a number, e.g. 제12조.)")
        private String number;

        @NotBlank(message = "조문 제목(한국어)을 입력해 주십시오. (Korean is the authoritative text.)")
        private String headingKo;

        private String headingEn;

        @NotBlank(message = "조문 본문(한국어)을 입력해 주십시오.")
        private String bodyKo;

        private String bodyEn;

        @Schema(example = "제12조")
        public String getNumber() {
            return number;
        }

        public void setNumber(String value) {
            this.number = value;
        }

        public String getHeadingKo() {
            return headingKo;
        }

        public void setHeadingKo(String value) {
            this.headingKo = value;
        }

        public String getHeadingEn() {
            return headingEn;
        }

        public void setHeadingEn(String value) {
            this.headingEn = value;
        }

        public String getBodyKo() {
            return bodyKo;
        }

        public void setBodyKo(String value) {
            this.bodyKo = value;
        }

        public String getBodyEn() {
            return bodyEn;
        }

        public void setBodyEn(String value) {
            this.bodyEn = value;
        }
    }

    /** A personal receipt. */
    @Schema(name = "AcknowledgeEmploymentRulesRequest")
    public static class AcknowledgeRequest {

        @NotBlank
        private String employeeId;

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate acknowledgedOn;

        @Schema(description = "Must be your own. Nobody may record an acknowledgement on "
                + "another person's behalf.")
        public String getEmployeeId() {
            return employeeId;
        }

        public void setEmployeeId(String value) {
            this.employeeId = value;
        }

        @Schema(description = "Defaults to today.")
        public LocalDate getAcknowledgedOn() {
            return acknowledgedOn;
        }

        public void setAcknowledgedOn(LocalDate value) {
            this.acknowledgedOn = value;
        }
    }
}
