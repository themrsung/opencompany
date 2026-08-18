package com.coreintra.auth.repository;

import com.coreintra.auth.entity.ApiKey;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiKeyRepository extends JpaRepository<ApiKey, String> {

    /** Indexed lookup on the clear prefix; the secret is then hash-compared. */
    Optional<ApiKey> findByKeyPrefix(String keyPrefix);

    List<ApiKey> findByAccountIdAndRevokedAtIsNull(String accountId);
}
