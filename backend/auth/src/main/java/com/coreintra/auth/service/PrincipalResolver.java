package com.coreintra.auth.service;

import com.coreintra.auth.entity.ApiKey;
import com.coreintra.auth.entity.AuthSession;
import com.coreintra.core.org.UserAccount;
import com.coreintra.core.org.repository.UserAccountRepository;
import com.coreintra.core.permission.PermissionPrincipal;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a credential into the {@link PermissionPrincipal} the evaluator judges.
 *
 * <p>One place, three credentials — a session cookie, an API key, and (through
 * the same session path) a temporary master session. Every entry point into the
 * system resolves its caller here, so there is nowhere for a second, subtly
 * different idea of "who is asking" to grow.
 *
 * <p>Resolution deliberately re-reads the account rather than trusting anything
 * cached on the session row. A deactivated account, a demoted master or an
 * expired support session must stop working on the <em>next</em> request, not
 * whenever the session happens to expire.
 */
@Service
public class PrincipalResolver {

    /**
     * Whether an account is currently acting as a temporary master.
     *
     * <p>An SPI rather than a direct dependency: the temporary-master feature
     * lives in the platform runtime, and pointing the authentication module at
     * it would couple the two in the wrong direction. The default answers "no",
     * so an installation without the feature wired behaves as though nobody is
     * a support session — which is the safe answer.
     */
    public interface TemporaryMasterDirectory {
        boolean isLiveTemporaryMaster(String accountId);
    }

    private static final TemporaryMasterDirectory NOBODY = new TemporaryMasterDirectory() {
        @Override
        public boolean isLiveTemporaryMaster(String accountId) {
            return false;
        }
    };

    private final SessionService sessions;
    private final ApiKeyService apiKeys;
    private final UserAccountRepository accounts;
    private final TemporaryMasterDirectory temporaryMasters;

    public PrincipalResolver(SessionService sessions, ApiKeyService apiKeys,
            UserAccountRepository accounts,
            Optional<TemporaryMasterDirectory> temporaryMasters) {
        this.sessions = sessions;
        this.apiKeys = apiKeys;
        this.accounts = accounts;
        this.temporaryMasters = temporaryMasters.orElse(NOBODY);
    }

    /** Thrown when a credential does not resolve to a usable account. */
    public static class UnresolvedPrincipalException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UnresolvedPrincipalException() {
            super("This request requires an authenticated caller.");
        }
    }

    /**
     * Resolves a browser session from its short-lived access token.
     *
     * @throws UnresolvedPrincipalException if the token or the account behind it
     *         is not usable
     */
    @Transactional(readOnly = true)
    public PermissionPrincipal fromAccessToken(String accessToken) {
        AuthSession session;
        try {
            session = sessions.authenticate(accessToken);
        } catch (SessionService.InvalidSessionException e) {
            throw new UnresolvedPrincipalException();
        }
        return forAccount(session.accountId());
    }

    /**
     * Resolves a service account from its API key.
     *
     * <p>The key's scopes travel onto the principal, where they <em>narrow</em>
     * the account's grants and never widen them.
     */
    @Transactional
    public PermissionPrincipal fromApiKey(String presentedKey) {
        ApiKey key;
        try {
            key = apiKeys.verify(presentedKey);
        } catch (ApiKeyService.InvalidApiKeyException e) {
            throw new UnresolvedPrincipalException();
        }
        UserAccount account = usableAccount(key.accountId());
        // Always a service account, even when the key belongs to a person's
        // account: a key is a non-interactive credential and is audited as one.
        return PermissionPrincipal.serviceAccount(account.id(), account.displayName(),
                key.scopeSet());
    }

    private PermissionPrincipal forAccount(String accountId) {
        UserAccount account = usableAccount(accountId);
        if (temporaryMasters.isLiveTemporaryMaster(account.id())) {
            // Flagged separately so that every read this principal performs is
            // logged, which is what makes the support session survivable.
            return PermissionPrincipal.temporaryMaster(account.id(), account.displayName());
        }
        if (account.isMaster()) {
            return PermissionPrincipal.master(account.id(), account.displayName(),
                    account.employeeId());
        }
        return PermissionPrincipal.user(account.id(), account.displayName(), account.employeeId());
    }

    private UserAccount usableAccount(String accountId) {
        Optional<UserAccount> found = accounts.findById(accountId);
        if (!found.isPresent() || !found.get().isActive()) {
            throw new UnresolvedPrincipalException();
        }
        return found.get();
    }
}
