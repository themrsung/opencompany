package com.coreintra.app.api.approval;

import com.coreintra.app.api.paging.Cursors;
import com.coreintra.app.api.permission.CurrentPrincipal;
import com.coreintra.approval.service.ApprovalInboxService;
import com.coreintra.compat.Texts;
import com.coreintra.core.permission.PermissionPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 결재함 — the screen this product is judged on.
 *
 * <p>One endpoint, one round trip, three lists and five counts. §12 says the
 * approval inbox and the who's-in board are the two screens that decide whether
 * people like this software, and the way an inbox stops being liked is that it
 * takes four requests to draw and the badge is wrong for a second afterwards.
 *
 * <p>Reading somebody else's inbox is a sensitive read and is permission
 * checked: it lists what a person has been spending and asking for. Reading your
 * own never is — an account that could not see its own 결재 대기 list could not
 * do its job.
 */
@RestController
@RequestMapping("/api/v1/approvals")
@Tag(name = "결재 — inbox",
        description = "The 결재함: pending on me, drafted by me, copied to me, with the "
                + "badge counts, in one request.")
public class ApprovalInboxController {

    private final ApprovalInboxService inbox;
    private final CurrentPrincipal currentPrincipal;

    public ApprovalInboxController(ApprovalInboxService inbox, CurrentPrincipal currentPrincipal) {
        this.inbox = inbox;
        this.currentPrincipal = currentPrincipal;
    }

    @GetMapping("/inbox")
    @Operation(summary = "The whole 결재함 in one request",
            description = "Returns 결재 대기 (oldest first), 내가 올린 문서 and 참조 문서 "
                    + "(both newest first), plus the counts the navigation badge needs before "
                    + "any list is rendered. A document drafted by you and also copied to you "
                    + "appears only under draftedByMe; one that is drafted by you and pending "
                    + "on you appears under both, because it genuinely is both. Reading "
                    + "another account's inbox requires approval.document:read.")
    public ApprovalInboxResponse inbox(
            @RequestParam(name = "companyId") String companyId,
            @RequestParam(name = "accountId", required = false) String accountId,
            @RequestParam(name = "limit", required = false) Integer limit) {

        PermissionPrincipal caller = currentPrincipal.require();
        // Defaulting to the caller is what makes the ordinary case a bare GET.
        // Naming somebody else is allowed, and checked, inside the service.
        String subject = Texts.isBlank(accountId) ? caller.accountId() : Texts.strip(accountId);
        int perList = Cursors.pageSize(limit, ApiWire.DEFAULT_PAGE_SIZE, ApiWire.MAX_PAGE_SIZE);
        return ApprovalInboxResponse.from(
                inbox.load(caller, companyId, subject, perList), perList);
    }
}
