package com.coreintra.auth.service;

import com.coreintra.auth.crypto.SecretHasher;
import com.coreintra.auth.entity.ApiKey;
import com.coreintra.auth.repository.ApiKeyRepository;
import com.coreintra.compat.Immutables;
import com.coreintra.compat.Texts;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and verifies the scoped keys that service accounts, client modules and
 * MCP clients authenticate with.
 *
 * <h2>The secret is shown once</h2>
 *
 * <p>{@link #issue} is the only moment the plaintext exists. It is not stored,
 * not logged, and not recoverable; a lost key is replaced, not retrieved. That
 * is the same rule the recovery codes follow and for the same reason — a system
 * that can show you a credential again can show it to someone else.
 */
@Service
public class ApiKeyService {

    /** {@code ci_} marks the string as ours in a log or a leaked config file. */
    private static final String PREFIX_MARKER = "ci_";
    private static final int SECRET_BYTES = 32;

    /**
     * Bytes behind the public prefix.
     *
     * <p>Six, which is far below the 128-bit floor {@code SecretHasher.randomToken}
     * enforces — correctly, because that method mints bearer secrets and this is
     * not one. The prefix is stored in clear precisely so it can be read: it
     * identifies a key in a log or a config file without revealing it. It needs
     * to be unique, not unguessable.
     *
     * <p>Calling {@code randomToken(6)} here is what the first version did, and
     * it threw on every single issue call — the feature had never worked end to
     * end because nothing exercised it. Its own generator, with its own reason
     * for its own length, is the fix.
     */
    private static final int PREFIX_BYTES = 6;

    private static final SecureRandom PREFIX_RANDOM = new SecureRandom();

    private final ApiKeyRepository keys;

    public ApiKeyService(ApiKeyRepository keys) {
        this.keys = keys;
    }

    /** The plaintext key and the row it belongs to. Returned once, at issue. */
    public static final class IssuedKey {
        private final ApiKey record;
        private final String plaintext;

        IssuedKey(ApiKey record, String plaintext) {
            this.record = record;
            this.plaintext = plaintext;
        }

        public ApiKey record() {
            return record;
        }

        /** {@code ci_<prefix>_<secret>}. Show it once and never store it. */
        public String plaintext() {
            return plaintext;
        }
    }

    /** Thrown when a presented key is unknown, expired, revoked or wrong. */
    public static class InvalidApiKeyException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        InvalidApiKeyException() {
            // One message for every reason. Telling a caller that the key exists
            // but expired confirms the key is real, which is worth something to
            // whoever found it in a log.
            super("This API key is not valid.");
        }
    }

    @Transactional
    public IssuedKey issue(String accountId, String name, Set<String> scopes, String createdBy,
            OffsetDateTime expiresAt) {
        if (Texts.isBlank(accountId)) {
            throw new IllegalArgumentException("accountId is required");
        }
        if (Texts.isBlank(name)) {
            throw new IllegalArgumentException("give the key a name — an unnamed key is one "
                    + "nobody dares revoke");
        }
        String prefix = randomPrefix();
        String secret = SecretHasher.randomToken(SECRET_BYTES);

        ApiKey record = new ApiKey(UUID.randomUUID().toString(), accountId, name, prefix,
                SecretHasher.hash(secret), joinScopes(scopes));
        record.setCreatedBy(createdBy);
        record.setExpiresAt(expiresAt);
        keys.save(record);

        return new IssuedKey(record, PREFIX_MARKER + prefix + "_" + secret);
    }

    /**
     * Verifies a presented key.
     *
     * <p>Lookup is by the clear prefix and then one hash comparison, rather than
     * hashing the presented secret and searching: the stored hashes are salted
     * per row, so they are not lookup keys, and scanning every key ever issued
     * on every request would get slower for the rest of the installation's life.
     *
     * @throws InvalidApiKeyException for every failure, without saying which
     */
    @Transactional
    public ApiKey verify(String presented) {
        Parsed parsed = parse(presented);
        if (parsed == null) {
            throw new InvalidApiKeyException();
        }
        Optional<ApiKey> found = keys.findByKeyPrefix(parsed.prefix);
        if (!found.isPresent()) {
            throw new InvalidApiKeyException();
        }
        ApiKey key = found.get();
        if (!SecretHasher.matches(parsed.secret, key.secretHash())
                || !key.isUsable(OffsetDateTime.now())) {
            throw new InvalidApiKeyException();
        }
        key.recordUse();
        keys.save(key);
        return key;
    }

    @Transactional
    public void revoke(String keyId) {
        Optional<ApiKey> found = keys.findById(keyId);
        if (found.isPresent()) {
            found.get().revoke();
            keys.save(found.get());
        }
    }

    /** Live keys for an account, for the "one-click revoke" list. Never includes secrets. */
    @Transactional(readOnly = true)
    public List<ApiKey> activeKeys(String accountId) {
        return keys.findByAccountIdAndRevokedAtIsNull(accountId);
    }

    /** Short, URL-safe, and not a secret. Unique enough that two keys are told apart. */
    private static String randomPrefix() {
        byte[] material = new byte[PREFIX_BYTES];
        PREFIX_RANDOM.nextBytes(material);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(material);
    }

    private static String joinScopes(Set<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            // An empty scope set is a key that can authenticate and do nothing,
            // which is a deliberate and useful starting point: capabilities get
            // ticked on afterwards, the same way the temporary master works.
            return "";
        }
        StringBuilder joined = new StringBuilder();
        for (String scope : Immutables.setCopyOf(scopes)) {
            if (joined.length() > 0) {
                joined.append(',');
            }
            joined.append(Texts.strip(scope));
        }
        return joined.toString();
    }

    private static final class Parsed {
        private final String prefix;
        private final String secret;

        Parsed(String prefix, String secret) {
            this.prefix = prefix;
            this.secret = secret;
        }
    }

    private static Parsed parse(String presented) {
        if (presented == null || !presented.startsWith(PREFIX_MARKER)) {
            return null;
        }
        String body = presented.substring(PREFIX_MARKER.length());
        int separator = body.indexOf('_');
        if (separator <= 0 || separator == body.length() - 1) {
            return null;
        }
        return new Parsed(body.substring(0, separator), body.substring(separator + 1));
    }
}
