package com.coreintra.core.service;

import com.coreintra.core.org.UserAccount;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Accounts, read-only, for the two questions this package actually asks.
 *
 * <p>Nothing here writes an account. Creating one, linking it to an employee,
 * and the "never fewer than one active master" invariant all belong to
 * {@code com.coreintra.auth.service.MasterAccountService} and the authentication
 * service beside it - master is inseparable from credentials and lockout, and a
 * second service holding the same invariant is a second answer to it.
 *
 * <p>What the org side needs is narrower: a grant can be attached to one
 * account, and the grant book has to resolve that account to the person behind
 * it before it can decide whether the caller's authority reaches them.
 */
public interface UserAccountCatalogRepository extends Repository<UserAccount, String> {

    Optional<UserAccount> findById(String id);

    /** At most one account per employee - {@code user_account_employee_unique}. */
    Optional<UserAccount> findByEmployeeId(String employeeId);
}
