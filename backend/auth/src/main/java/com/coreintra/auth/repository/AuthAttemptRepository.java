package com.coreintra.auth.repository;

import com.coreintra.auth.entity.AuthAttempt;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthAttemptRepository extends JpaRepository<AuthAttempt, String> {

    /**
     * Recent failures for a username, newest first.
     *
     * <p>Keyed on the submitted username rather than a resolved account, so
     * attempts against names that do not exist are throttled identically —
     * otherwise the throttle itself reveals which usernames are real.
     */
    @Query("select a from AuthAttempt a "
            + "where a.username = :username and a.attemptedAt > :since and a.succeeded = false "
            + "order by a.attemptedAt desc")
    List<AuthAttempt> findRecentFailures(@Param("username") String username,
            @Param("since") OffsetDateTime since);
}
