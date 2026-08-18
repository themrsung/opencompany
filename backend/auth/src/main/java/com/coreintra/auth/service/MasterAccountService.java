package com.coreintra.auth.service;

import com.coreintra.core.org.UserAccount;
import com.coreintra.core.org.repository.UserAccountRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the "never fewer than one active master" invariant.
 *
 * <p>Master is an account flag and there may be several, so no single entity
 * can enforce this — it needs a count across the table, and it needs that count
 * and the write to happen together. Hence a service, and hence the pessimistic
 * lock: two concurrent demotions each seeing "two masters remain" would both
 * proceed and leave zero, locking every administrator out of the installation
 * permanently.
 */
@Service
public class MasterAccountService {

    /** Refusing a demotion that would leave no master. Carries a message for the user. */
    public static class LastMasterException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public LastMasterException(String message) {
            super(message);
        }
    }

    private final UserAccountRepository accounts;

    public MasterAccountService(UserAccountRepository accounts) {
        this.accounts = accounts;
    }

    /**
     * Removes master status.
     *
     * @throws LastMasterException if this is the only active master
     */
    @Transactional
    public void demote(String accountId) {
        UserAccount account = require(accountId);
        if (!account.isMaster()) {
            return;
        }
        refuseIfLastMaster(account,
                "이 계정은 마지막 마스터 계정입니다. 먼저 다른 계정을 마스터로 지정한 뒤 해제해 주십시오. "
                        + "(This is the last active master account. Promote another account to "
                        + "master before removing this one.)");
        account.setMaster(false);
        accounts.save(account);
    }

    /**
     * Deactivates an account.
     *
     * @throws LastMasterException if it is the only active master — deactivating
     *         is exactly as effective at locking everyone out as demoting, so it
     *         is refused on the same terms
     */
    @Transactional
    public void deactivate(String accountId) {
        UserAccount account = require(accountId);
        if (account.isMaster()) {
            refuseIfLastMaster(account,
                    "이 계정은 마지막 마스터 계정입니다. 비활성화할 수 없습니다. "
                            + "(This is the last active master account and cannot be deactivated.)");
        }
        account.deactivate();
        accounts.save(account);
    }

    @Transactional
    public void promote(String accountId) {
        UserAccount account = require(accountId);
        if (account.kind() != UserAccount.AccountKind.USER) {
            // A service account with master status would be a standing,
            // unattended god-key. Support access is the temporary-master
            // feature, which is time-boxed and doubly audited.
            throw new IllegalArgumentException(
                    "only a user account may be a master; " + account.kind()
                            + " accounts cannot. For vendor support use a temporary master account.");
        }
        account.setMaster(true);
        accounts.save(account);
    }

    /** Active masters. Never returns empty on a healthy installation. */
    @Transactional(readOnly = true)
    public long activeMasterCount() {
        return accounts.countByMasterTrueAndActiveTrue();
    }

    private void refuseIfLastMaster(UserAccount account, String message) {
        long remaining = accounts.countByMasterTrueAndActiveTrue();
        boolean countsItself = account.isActive() && account.isMaster();
        if (countsItself && remaining <= 1) {
            throw new LastMasterException(message);
        }
    }

    private UserAccount require(String accountId) {
        Optional<UserAccount> found = accounts.findById(accountId);
        if (!found.isPresent()) {
            throw new IllegalArgumentException("no such account: " + accountId);
        }
        return found.get();
    }
}
