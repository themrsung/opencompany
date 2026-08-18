package com.coreintra.auth.service;

import com.coreintra.auth.crypto.SecretHasher;
import com.coreintra.auth.entity.AuthSession;
import com.coreintra.auth.repository.AuthSessionRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues, rotates and revokes sessions.
 *
 * <h2>Rotation with reuse detection</h2>
 *
 * <p>Each refresh mints a new refresh token and retires the old one. If a
 * retired token is ever presented, the only explanation is that it was captured
 * — the legitimate client already exchanged it and holds the successor. So the
 * response is not "refuse this request" but "revoke the entire chain", forcing
 * both the attacker and the real user to re-authenticate.
 *
 * <p>That is deliberately more disruptive than refusing one request. A stolen
 * refresh token that merely fails once leaves the attacker free to retry, and
 * leaves nobody aware anything happened.
 *
 * <p>Tokens are stored hashed. A database dump yields no usable session.
 */
@Service
public class SessionService {

    /** Short, because it is not revocable until it expires. */
    public static final Duration ACCESS_TOKEN_LIFETIME = Duration.ofMinutes(15);

    /** Long enough that a normal working day needs no re-authentication. */
    public static final Duration REFRESH_TOKEN_LIFETIME = Duration.ofDays(14);

    private static final int TOKEN_BYTES = 32;

    /** A freshly issued pair. The plaintext exists only here and in the response. */
    public static final class IssuedSession {
        private final String sessionId;
        private final String refreshToken;
        private final OffsetDateTime refreshExpiresAt;
        private final String accessToken;
        private final OffsetDateTime accessExpiresAt;

        IssuedSession(String sessionId, String refreshToken, OffsetDateTime refreshExpiresAt,
                String accessToken, OffsetDateTime accessExpiresAt) {
            this.sessionId = sessionId;
            this.refreshToken = refreshToken;
            this.refreshExpiresAt = refreshExpiresAt;
            this.accessToken = accessToken;
            this.accessExpiresAt = accessExpiresAt;
        }

        public String sessionId() {
            return sessionId;
        }

        /** Plaintext, to be set as an HttpOnly SameSite cookie and never logged. */
        public String refreshToken() {
            return refreshToken;
        }

        public OffsetDateTime refreshExpiresAt() {
            return refreshExpiresAt;
        }

        /** Plaintext, short-lived, HttpOnly. What an ordinary request presents. */
        public String accessToken() {
            return accessToken;
        }

        public OffsetDateTime accessExpiresAt() {
            return accessExpiresAt;
        }
    }

    /** Thrown when a refresh token is unusable. Never says which of the reasons applied. */
    public static class InvalidSessionException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public InvalidSessionException(String message) {
            super(message);
        }
    }

    private final AuthSessionRepository sessions;

    public SessionService(AuthSessionRepository sessions) {
        this.sessions = sessions;
    }

    @Transactional
    public IssuedSession issue(String accountId, String userAgent, String ipAddress) {
        String sessionId = UUID.randomUUID().toString();
        String secret = SecretHasher.randomToken(TOKEN_BYTES);
        OffsetDateTime expiry = OffsetDateTime.now().plus(REFRESH_TOKEN_LIFETIME);

        AuthSession session = new AuthSession(
                sessionId, accountId, UUID.randomUUID().toString(),
                SecretHasher.hash(secret), expiry);
        session.setUserAgent(truncate(userAgent, 400));
        session.setIpAddress(truncate(ipAddress, 64));

        String accessSecret = mintAccessToken(session, OffsetDateTime.now());
        sessions.save(session);

        return new IssuedSession(sessionId, composeToken(sessionId, secret), expiry,
                composeToken(sessionId, accessSecret), session.accessExpiresAt());
    }

    /**
     * Puts a fresh access secret on the session and returns the plaintext half.
     *
     * <p>Same wire shape as the refresh token, and for the same reason: the
     * session id travels with it, so verification is a primary-key hit rather
     * than a scan over every session ever issued. The stored hashes are salted
     * per row, so they cannot be looked up by hashing what was presented.
     */
    private static String mintAccessToken(AuthSession session, OffsetDateTime now) {
        String accessSecret = SecretHasher.randomToken(TOKEN_BYTES);
        session.issueAccessToken(SecretHasher.hash(accessSecret), now.plus(ACCESS_TOKEN_LIFETIME));
        return accessSecret;
    }

    /**
     * Resolves the access token an ordinary request presented.
     *
     * <p>Checked against the registry on every request rather than validated
     * from a signature. That is the whole reason the token is opaque: sign-out,
     * a master's remote sign-out, and the temporary-master "Revoke now" button
     * all have to take effect on the *next* request, and a self-validating token
     * would keep working until it expired.
     *
     * <p>An expired access token on a live session is the ordinary state that
     * triggers a refresh, so it throws the same way an unknown one does and the
     * caller decides whether to refresh — this method never says which of the
     * reasons applied.
     *
     * @throws InvalidSessionException if the token is unknown, tampered with,
     *         expired or revoked
     */
    @Transactional(readOnly = true)
    public AuthSession authenticate(String presentedAccessToken) {
        OffsetDateTime now = OffsetDateTime.now();
        int separator = presentedAccessToken == null ? -1 : presentedAccessToken.indexOf('.');
        if (separator <= 0) {
            throw new InvalidSessionException("This session is no longer valid. Please sign in again.");
        }
        String sessionId = presentedAccessToken.substring(0, separator);
        String secret = presentedAccessToken.substring(separator + 1);

        Optional<AuthSession> candidate = sessions.findById(sessionId);
        if (!candidate.isPresent()) {
            throw new InvalidSessionException("This session is no longer valid. Please sign in again.");
        }
        AuthSession matched = candidate.get();
        if (matched.accessTokenHash() == null
                || !SecretHasher.matches(secret, matched.accessTokenHash())
                || !matched.isAccessUsable(now)) {
            // No chain revocation here, unlike the refresh path. An access token
            // that has merely aged out is the normal case many times a day, and
            // burning the session for it would sign everyone out every fifteen
            // minutes.
            throw new InvalidSessionException("This session is no longer valid. Please sign in again.");
        }
        return matched;
    }

    /**
     * The wire form of a refresh token: {@code <sessionId>.<secret>}.
     *
     * <p>The session id travels with the token so lookup is a primary-key hit.
     * Stored hashes are salted per row and therefore cannot be looked up by
     * hashing the presented token, and scanning the session table to find a
     * match would grow with every session ever issued.
     *
     * <p>The id half is not a secret and grants nothing on its own — the secret
     * half is what is verified, against a salted hash.
     */
    private static String composeToken(String sessionId, String secret) {
        return sessionId + "." + secret;
    }

    /**
     * Exchanges a refresh token for a new one.
     *
     * <p>{@code noRollbackFor} is load-bearing, not tidiness. The reuse-detection
     * paths below revoke the chain and <em>then</em> throw. Under the default
     * rollback-on-RuntimeException rule, that throw would undo the revocation —
     * so a stolen token would be refused once and the chain would stay alive for
     * the attacker's next attempt. The security response has to survive the
     * exception that reports it.
     *
     * @throws InvalidSessionException if the token is unknown, expired, revoked,
     *         or superseded — the last two of which also revoke the whole chain
     */
    @Transactional(noRollbackFor = InvalidSessionException.class)
    public IssuedSession refresh(String presentedRefreshToken, String userAgent, String ipAddress) {
        OffsetDateTime now = OffsetDateTime.now();

        int separator = presentedRefreshToken == null ? -1 : presentedRefreshToken.indexOf('.');
        if (separator <= 0) {
            throw new InvalidSessionException("This session is no longer valid. Please sign in again.");
        }
        String sessionId = presentedRefreshToken.substring(0, separator);
        String secret = presentedRefreshToken.substring(separator + 1);

        Optional<AuthSession> candidate = sessions.findById(sessionId);
        if (!candidate.isPresent()) {
            throw new InvalidSessionException("This session is no longer valid. Please sign in again.");
        }
        AuthSession matched = candidate.get();

        if (!SecretHasher.matches(secret, matched.refreshTokenHash())) {
            // The id is right but the secret is not: either a guess, or a
            // superseded token from this same session. Both mean the lineage
            // should not continue.
            revokeChain(matched.chainId(), "refresh_secret_mismatch");
            throw new InvalidSessionException("This session is no longer valid. Please sign in again.");
        }

        if (matched.revokedAt() != null) {
            // A revoked session's token being presented means it was captured
            // before revocation, or the chain was already burned. Either way the
            // lineage is compromised.
            revokeChain(matched.chainId(), "reuse_after_revocation");
            throw new InvalidSessionException("This session is no longer valid. Please sign in again.");
        }
        if (!now.isBefore(matched.expiresAt())) {
            throw new InvalidSessionException("This session has expired. Please sign in again.");
        }

        String newSecret = SecretHasher.randomToken(TOKEN_BYTES);
        OffsetDateTime newExpiry = now.plus(REFRESH_TOKEN_LIFETIME);
        matched.rotate(SecretHasher.hash(newSecret), newExpiry);
        matched.setUserAgent(truncate(userAgent, 400));
        matched.setIpAddress(truncate(ipAddress, 64));
        // The access half rotates with the refresh half. Leaving the old access
        // token live after a refresh would hand an attacker who captured it a
        // second window it should not have.
        String accessSecret = mintAccessToken(matched, now);
        sessions.save(matched);

        // The old secret no longer verifies, so presenting it again lands in the
        // mismatch branch above and burns the chain. That is the reuse detection.
        return new IssuedSession(matched.id(), composeToken(matched.id(), newSecret), newExpiry,
                composeToken(matched.id(), accessSecret), matched.accessExpiresAt());
    }

    /** Revokes every session in a rotation lineage. */
    @Transactional
    public void revokeChain(String chainId, String reason) {
        for (AuthSession session : sessions.findByChainId(chainId)) {
            session.revoke(reason);
            sessions.save(session);
        }
    }

    /** Remote sign-out of one device from the active-session list. */
    @Transactional
    public void revoke(String sessionId, String reason) {
        Optional<AuthSession> found = sessions.findById(sessionId);
        if (found.isPresent()) {
            found.get().revoke(reason);
            sessions.save(found.get());
        }
    }

    /** Every device signed in, for the active-session list. */
    @Transactional(readOnly = true)
    public List<AuthSession> activeSessions(String accountId) {
        return sessions.findByAccountIdAndRevokedAtIsNull(accountId);
    }

    /** Signs an account out everywhere. Used on termination and on master revocation. */
    @Transactional
    public int revokeAllFor(String accountId, String reason) {
        List<AuthSession> active = sessions.findByAccountIdAndRevokedAtIsNull(accountId);
        for (AuthSession session : active) {
            session.revoke(reason);
            sessions.save(session);
        }
        return active.size();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
