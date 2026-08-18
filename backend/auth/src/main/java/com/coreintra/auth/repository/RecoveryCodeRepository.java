package com.coreintra.auth.repository;

import com.coreintra.auth.entity.RecoveryCode;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecoveryCodeRepository extends JpaRepository<RecoveryCode, String> {

    List<RecoveryCode> findByAccountIdAndUsedAtIsNull(String accountId);

    /** Backs the "n remaining" figure the UI shows. */
    long countByAccountIdAndUsedAtIsNull(String accountId);

    void deleteByAccountId(String accountId);
}
