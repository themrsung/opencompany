package com.coreintra.core.org.repository;

import com.coreintra.core.org.UserAccount;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountRepository extends JpaRepository<UserAccount, String> {

    Optional<UserAccount> findByUsername(String username);

    List<UserAccount> findByMasterTrueAndActiveTrue();

    /** Backs the "never fewer than one active master" invariant. */
    long countByMasterTrueAndActiveTrue();
}
