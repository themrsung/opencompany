package com.coreintra.app.api.approval;

import com.coreintra.app.api.permission.CurrentPrincipal;
import com.coreintra.approval.domain.ApprovalLineTemplate;
import com.coreintra.approval.service.ApprovalLineTemplateService;
import com.coreintra.approval.service.ApprovalPermissions;
import com.coreintra.core.permission.PermissionEvaluator;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionTarget;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 결재선 서식 — which line a document type gets, and why.
 *
 * <p>This is the "what will happen if I submit this?" endpoint. A drafter about
 * to file a 5,000,000원 지출결의서 can see that it will pick up a 이사 step and
 * read the rule that adds it, before submitting rather than after.
 *
 * <h2>The permission check is here, and it should not be</h2>
 *
 * <p>{@code ApprovalLineTemplateService.resolve} takes no principal and checks
 * nothing: it is an internal lookup that {@code ApprovalDocumentService} calls
 * after it has already authorised the drafter. Exposing it needed a gate, and
 * the gate is the same {@link PermissionEvaluator} everything else routes
 * through — there is no second mechanism here. But authorisation decided in a
 * controller is authorisation decided away from the domain object, which §3 is
 * against, and the honest fix is an overload on the service that takes a
 * principal. That is noted for the approval module rather than worked around
 * with a repository call.
 */
@RestController
@RequestMapping("/api/v1/approvals/line-templates")
@Tag(name = "결재 — line templates",
        description = "The 결재선 template for a document type as seen from an org unit, "
                + "including what an amount threshold would add to it.")
public class ApprovalLineTemplateController {

    private final ApprovalLineTemplateService templates;
    private final PermissionEvaluator permissions;
    private final CurrentPrincipal currentPrincipal;

    public ApprovalLineTemplateController(ApprovalLineTemplateService templates,
            PermissionEvaluator permissions, CurrentPrincipal currentPrincipal) {
        this.templates = templates;
        this.permissions = permissions;
        this.currentPrincipal = currentPrincipal;
    }

    @GetMapping
    @Operation(summary = "The 결재선 a document type would get",
            description = "Resolves the most specific template on the org unit's ancestor "
                    + "chain, falling back to the company default. Give an amount to see the "
                    + "steps the thresholds actually produce and the rules that produced them; "
                    + "the amount is an exact decimal string, because a rounding error here "
                    + "changes who has to sign. Refused with 409 when no template covers the "
                    + "document type at all — a submission with no line would be an approval "
                    + "nobody has to give.")
    public ApprovalLineTemplateView resolve(
            @RequestParam(name = "companyId") String companyId,
            @RequestParam(name = "documentType") String documentType,
            @RequestParam(name = "orgUnitId", required = false) String orgUnitId,
            @RequestParam(name = "amount", required = false) String amount,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = ApiWire.onDate(businessDate);
        permissions.check(caller, ApprovalPermissions.DOCUMENT_READ,
                PermissionTarget.builder()
                        .companyId(companyId)
                        .orgUnitId(orgUnitId)
                        .asOfBusinessDate(on)
                        .description("결재선 template for " + documentType)
                        .build()).orThrow();

        BigDecimal money = ApiWire.amount(amount, "amount");
        ApprovalLineTemplate template = templates.resolve(companyId, documentType, orgUnitId);
        return ApprovalLineTemplateView.from(template, money);
    }
}
