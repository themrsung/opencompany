package com.coreintra.auth.repository;

import com.coreintra.auth.entity.AuthSession;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthSessionRepository extends JpaRepository<AuthSession, String> {

    Optional<AuthSession> findByRefreshTokenHash(String refreshTokenHash);

    List<AuthSession> findByAccountIdAndRevokedAtIsNull(String accountId);

    /**
     * Every session in a rotation chain.
     *
     * <p>Used to revoke the lineage when a superseded refresh token is presented
     * again, which means it was captured.
     */
    List<AuthSession> findByChainId(String chainId);
}
