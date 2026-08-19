package com.coreintra.app.api.account;

import com.coreintra.app.api.org.BusinessDates;
import com.coreintra.app.api.permission.CurrentPrincipal;
import com.coreintra.auth.service.MasterAccountService;
import com.coreintra.core.org.UserAccount;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.service.OrgPermissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Master accounts — the flag, not a magic user id.
 *
 * <p>There may be several and there must never be none. {@code MasterAccountService}
 * owns that invariant with a counted, locked write, because two concurrent
 * demotions each seeing "two masters remain" would both proceed and lock every
 * administrator out of the installation permanently. This controller does not
 * re-check it; a second copy of an invariant is a second answer to it.
 *
 * <p>Master status buys no bypass. The evaluator does not branch on it: masters
 * hold broad grants like anybody else, so their authority shows up in the
 * explainer instead of hiding in a conditional, and a master whose grant is
 * revoked genuinely loses the ability.
 */
@RestController
@RequestMapping("/api/v1/account/masters")
@Tag(name = "Account — masters",
        description = "Promoting and demoting master accounts. Never fewer than one active "
                + "master; the last one cannot be removed.")
public class MasterAccountController {

    private final MasterAccountService masters;
    private final AccountSubjects subjects;
    private final CurrentPrincipal currentPrincipal;

    public MasterAccountController(MasterAccountService masters, AccountSubjects subjects,
            CurrentPrincipal currentPrincipal) {
        this.masters = masters;
        this.subjects = subjects;
        this.currentPrincipal = currentPrincipal;
    }

    @GetMapping
    @Operation(summary = "How many active masters there are",
            description = "The number the last-master refusal is decided on. A count rather "
                    + "than a list: who the masters are is an account question, and answering "
                    + "it here would be a second, unpermissioned way to read the account table.")
    public MasterCount count(
            @RequestParam(name = "businessDate", required = false) String businessDate) {
        // Reading the count is meaningful to any signed-in user: §8 promises that
        // everybody can see a live support session, and "how many masters are
        // there" is the same class of fact. It carries no identity.
        currentPrincipal.require();
        return new MasterCount(masters.activeMasterCount());
    }

    @PostMapping
    @Operation(summary = "Promote an account to master",
            description = "Requires admin.master:update over the account being promoted.")
    public ResponseEntity<Void> promote(
            @Valid @RequestBody PromotionRequest body,
            @RequestParam(name = "businessDate", required = false) String businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = BusinessDates.resolve(date(businessDate));
        UserAccount subject = subjects.authorise(caller, body.getAccountId(),
                OrgPermissions.MASTER_UPDATE, on, "promote to master");

        masters.promote(subject.id());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{accountId}")
    @Operation(summary = "Remove master status",
            description = "Refused with 409 when it would leave the installation with no active "
                    + "master. Demoting an account that is not a master is a no-op, not an error.")
    public ResponseEntity<Void> demote(
            @PathVariable String accountId,
            @RequestParam(name = "businessDate", required = false) String businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = BusinessDates.resolve(date(businessDate));
        UserAccount subject = subjects.authorise(caller, accountId,
                OrgPermissions.MASTER_UPDATE, on, "demote from master");

        masters.demote(subject.id());
        return ResponseEntity.noContent().build();
    }

    private static LocalDate date(String value) {
        return value == null ? null : LocalDate.parse(value);
    }

    /** How many active masters exist. */
    public static class MasterCount {
        private final long activeMasterCount;

        MasterCount(long activeMasterCount) {
            this.activeMasterCount = activeMasterCount;
        }

        /** Never zero on a working installation. */
        public long getActiveMasterCount() {
            return activeMasterCount;
        }
    }

    /** Who to promote. */
    public static class PromotionRequest {
        @NotBlank
        private String accountId;

        public String getAccountId() {
            return accountId;
        }

        public void setAccountId(String value) {
            this.accountId = value;
        }
    }
}
