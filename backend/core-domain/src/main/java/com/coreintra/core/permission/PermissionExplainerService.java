package com.coreintra.core.permission;

import com.coreintra.core.org.UserAccount;
import com.coreintra.core.org.repository.UserAccountRepository;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers "why can this user do this?" and "what can this user do?".
 *
 * <p>Both questions are asked constantly by admins and neither is answerable by
 * reading the database directly: grants arrive from four sources, denies
 * override allows within their scope, and scope is resolved against a position
 * as it stood on a date that may not be today.
 *
 * <p>Reading someone else's effective permissions is itself a sensitive read.
 * The caller must hold {@code admin.permission:read}, checked here against the
 * subject's own org unit — so a 팀장 can explain their own team without being
 * able to enumerate the 대표's authority.
 */
@Service
public class PermissionExplainerService {

    /** The permission required to inspect another account's authority. */
    public static final PermissionKey INSPECT = PermissionKey.of("admin.permission", "read");

    private final PermissionEvaluator evaluator;
    private final UserAccountRepository accounts;
    private final OrgDirectory orgDirectory;

    public PermissionExplainerService(PermissionEvaluator evaluator, UserAccountRepository accounts,
            OrgDirectory orgDirectory) {
        this.evaluator = evaluator;
        this.accounts = accounts;
        this.orgDirectory = orgDirectory;
    }

    /**
     * Everything {@code subjectAccountId} may do as of a date.
     *
     * @throws PermissionDeniedException if the caller may not inspect the subject
     */
    @Transactional(readOnly = true)
    public EffectivePermissions explainAccount(PermissionPrincipal caller, String subjectAccountId,
            LocalDate asOf) {
        PermissionPrincipal subject = loadPrincipal(subjectAccountId);
        authoriseInspection(caller, subject, asOf);
        return evaluator.effectivePermissions(subject, asOf);
    }

    /**
     * Why one specific decision came out the way it did.
     *
     * <p>Returns the decision whether it allowed or denied — a denial with its
     * reasoning is the more useful answer, and throwing on it would make the
     * explainer unable to explain the case admins actually ask about.
     */
    @Transactional(readOnly = true)
    public PermissionDecision explainDecision(PermissionPrincipal caller, String subjectAccountId,
            PermissionKey key, PermissionTarget target) {
        PermissionPrincipal subject = loadPrincipal(subjectAccountId);
        authoriseInspection(caller, subject, target.asOfBusinessDate());
        return evaluator.check(subject, key, target);
    }

    /** Inspecting your own authority never needs a permission. */
    private void authoriseInspection(PermissionPrincipal caller, PermissionPrincipal subject,
            LocalDate asOf) {
        if (caller.accountId().equals(subject.accountId())) {
            return;
        }
        PrincipalOrgState subjectState = orgDirectory.resolve(subject, asOf);
        PermissionTarget target = PermissionTarget.builder()
                .companyId(firstOrNull(subjectState.companyIds()))
                .orgUnitId(firstOrNull(subjectState.orgUnitIds()))
                .ownerEmployeeId(subject.employeeId())
                .asOfBusinessDate(asOf)
                .description("effective permissions of " + subject.accountId())
                .build();
        evaluator.check(caller, INSPECT, target).orThrow();
    }

    private static String firstOrNull(java.util.Set<String> values) {
        return values.isEmpty() ? null : values.iterator().next();
    }

    private PermissionPrincipal loadPrincipal(String accountId) {
        Optional<UserAccount> found = accounts.findById(accountId);
        if (!found.isPresent()) {
            throw new IllegalArgumentException("no such account: " + accountId);
        }
        UserAccount account = found.get();
        switch (account.kind()) {
            case SERVICE_ACCOUNT:
                return PermissionPrincipal.serviceAccount(
                        account.id(), account.displayName(), null);
            case TEMPORARY_MASTER:
                return PermissionPrincipal.temporaryMaster(account.id(), account.displayName());
            case USER:
            default:
                return account.isMaster()
                        ? PermissionPrincipal.master(account.id(), account.displayName(),
                                account.employeeId())
                        : PermissionPrincipal.user(account.id(), account.displayName(),
                                account.employeeId());
        }
    }
}
