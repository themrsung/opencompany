package com.coreintra.app.api.account;

import com.coreintra.app.api.org.BusinessDates;
import com.coreintra.app.api.org.Pages;
import com.coreintra.app.api.paging.CursorPage;
import com.coreintra.app.api.permission.CurrentPrincipal;
import com.coreintra.auth.entity.ApiKey;
import com.coreintra.auth.service.ApiKeyService;
import com.coreintra.compat.Immutables;
import com.coreintra.core.org.UserAccount;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.service.OrgPermissions;
import com.coreintra.core.service.RecordNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Scoped API keys — how a module, a scheduled job or an MCP client authenticates.
 *
 * <h2>The plaintext exists for one response</h2>
 *
 * <p>Issuing returns {@code ci_<prefix>_<secret>} and stores only a salted hash
 * of the secret. There is no endpoint that reveals it later and there cannot be
 * one; a client that loses it issues a new key and revokes the old. The prefix
 * stays visible so a key can be recognised in a log and revoked with one click
 * without anyone having to hold the secret to identify it.
 *
 * <h2>Scopes narrow, never widen</h2>
 *
 * <p>A key's scopes cut down what its account may do. Adding {@code mcp:write}
 * to a key belonging to somebody who cannot post an entry does not let the key
 * post one — the evaluator still decides against the account's grants, and the
 * scope only decides which tools the MCP server will even show. An empty scope
 * set is a deliberate and useful starting point: a key that authenticates and
 * can do nothing, with capabilities ticked on afterwards.
 */
@RestController
@RequestMapping("/api/v1/account/api-keys")
@Tag(name = "Account — API keys",
        description = "Scoped, hashed-at-rest credentials for service accounts, client modules "
                + "and MCP clients. The secret is shown once, at issue.")
public class ApiKeyController {

    private final ApiKeyService keys;
    private final AccountSubjects subjects;
    private final CurrentPrincipal currentPrincipal;

    public ApiKeyController(ApiKeyService keys, AccountSubjects subjects,
            CurrentPrincipal currentPrincipal) {
        this.keys = keys;
        this.subjects = subjects;
        this.currentPrincipal = currentPrincipal;
    }

    @GetMapping
    @Operation(summary = "Live keys on an account",
            description = "Never includes a secret. Revoked and expired keys are left out: this "
                    + "is the list somebody revokes from, not the audit trail.")
    public CursorPage<ApiKeyView> list(
            @RequestParam(name = "accountId", required = false) String accountId,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = BusinessDates.resolve(businessDate);
        UserAccount subject = subjects.authorise(caller, subjectId(caller, accountId),
                OrgPermissions.ACCOUNT_READ, on, "list API keys");

        return Pages.page(keys.activeKeys(subject.id()), KEYS, MAPPER, cursor, limit);
    }

    @PostMapping
    @Operation(summary = "Issue a key — the plaintext is in this response and nowhere else",
            description = "Store it now; only a salted hash is kept. The name is mandatory "
                    + "because an unnamed key is one nobody dares revoke. An absent expiresAt "
                    + "means the key does not expire, which is a decision worth making "
                    + "deliberately rather than by omission.")
    public IssuedApiKey issue(
            @RequestParam(name = "accountId", required = false) String accountId,
            @Valid @RequestBody IssueRequest body,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = BusinessDates.resolve(businessDate);
        UserAccount subject = subjects.authorise(caller, subjectId(caller, accountId),
                OrgPermissions.ACCOUNT_UPDATE, on, "issue API key");

        ApiKeyService.IssuedKey issued = keys.issue(subject.id(), body.getName(),
                Immutables.setCopyOf(body.getScopes()), caller.accountId(),
                body.getExpiresAt());
        return new IssuedApiKey(ApiKeyView.from(issued.record()), issued.plaintext());
    }

    @DeleteMapping("/{keyId}")
    @Operation(summary = "Revoke a key",
            description = "Takes effect on the next request the key makes. The row stays, marked "
                    + "revoked, so a key that turns up in a log later can still be identified.")
    public ResponseEntity<Void> revoke(
            @PathVariable String keyId,
            @RequestParam(name = "accountId", required = false) String accountId,
            @RequestParam(name = "businessDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        PermissionPrincipal caller = currentPrincipal.require();
        LocalDate on = BusinessDates.resolve(businessDate);
        UserAccount subject = subjects.authorise(caller, subjectId(caller, accountId),
                OrgPermissions.ACCOUNT_UPDATE, on, "revoke API key");

        // ApiKeyService.revoke takes a bare key id and asks no questions, so the
        // ownership check has to happen here. Without it, authority over one
        // account would be authority to revoke any key in the installation by
        // id - a denial of service against a module nobody could explain.
        requireOwned(subject.id(), keyId);
        keys.revoke(keyId);
        return ResponseEntity.noContent().build();
    }

    private void requireOwned(String accountId, String keyId) {
        for (ApiKey key : keys.activeKeys(accountId)) {
            if (key.id().equals(keyId)) {
                return;
            }
        }
        throw new RecordNotFoundException("no live API key " + keyId + " on this account");
    }

    private static String subjectId(PermissionPrincipal caller, String requested) {
        return requested == null ? caller.accountId() : requested;
    }

    /** Newest first is wrong here: an id-stable order is what a cursor walk needs. */
    private static final Pages.Keys<ApiKey> KEYS = new Pages.Keys<ApiKey>() {
        @Override
        public String sortKey(ApiKey key) {
            return key.createdAt() == null ? "" : key.createdAt().toString();
        }

        @Override
        public String id(ApiKey key) {
            return key.id();
        }
    };

    private static final Function<ApiKey, ApiKeyView> MAPPER = new Function<ApiKey, ApiKeyView>() {
        @Override
        public ApiKeyView apply(ApiKey key) {
            return ApiKeyView.from(key);
        }
    };

    /** A key as everyone but its issuer sees it: no secret, ever. */
    public static class ApiKeyView {
        private final String id;
        private final String accountId;
        private final String name;
        private final String keyPrefix;
        private final Set<String> scopes;
        private final String createdAt;
        private final String createdBy;
        private final String expiresAt;
        private final String lastUsedAt;

        ApiKeyView(String id, String accountId, String name, String keyPrefix, Set<String> scopes,
                String createdAt, String createdBy, String expiresAt, String lastUsedAt) {
            this.id = id;
            this.accountId = accountId;
            this.name = name;
            this.keyPrefix = keyPrefix;
            this.scopes = scopes;
            this.createdAt = createdAt;
            this.createdBy = createdBy;
            this.expiresAt = expiresAt;
            this.lastUsedAt = lastUsedAt;
        }

        static ApiKeyView from(ApiKey key) {
            return new ApiKeyView(key.id(), key.accountId(), key.name(), key.keyPrefix(),
                    key.scopeSet(), text(key.createdAt()), key.createdBy(),
                    text(key.expiresAt()), text(key.lastUsedAt()));
        }

        private static String text(OffsetDateTime value) {
            return value == null ? null : value.toString();
        }

        public String getId() {
            return id;
        }

        public String getAccountId() {
            return accountId;
        }

        /** What a human calls it. Mandatory at issue. */
        public String getName() {
            return name;
        }

        /** The visible half of the credential, for recognising it in a log. */
        public String getKeyPrefix() {
            return keyPrefix;
        }

        /** e.g. {@code mcp:read}, {@code mcp:write}. Narrows the account's grants. */
        public Set<String> getScopes() {
            return scopes;
        }

        /** Real UTC. */
        public String getCreatedAt() {
            return createdAt;
        }

        /** The account that issued it, which is not always the account it belongs to. */
        public String getCreatedBy() {
            return createdBy;
        }

        /** Null when the key does not expire. */
        public String getExpiresAt() {
            return expiresAt;
        }

        /** Null until first use. The column that tells you which keys are dead weight. */
        public String getLastUsedAt() {
            return lastUsedAt;
        }
    }

    /** The one response that carries a secret. */
    public static class IssuedApiKey {
        private final ApiKeyView key;
        private final String plaintext;

        IssuedApiKey(ApiKeyView key, String plaintext) {
            this.key = key;
            this.plaintext = plaintext;
        }

        public ApiKeyView getKey() {
            return key;
        }

        /**
         * {@code ci_<prefix>_<secret>}. Shown once; only a salted hash is stored,
         * so no later call — by anyone, with any permission — can return it.
         */
        public String getPlaintext() {
            return plaintext;
        }
    }

    /** A key to mint. */
    public static class IssueRequest {
        @NotBlank
        private String name;
        private Set<String> scopes;
        private OffsetDateTime expiresAt;

        public String getName() {
            return name;
        }

        public void setName(String value) {
            this.name = value;
        }

        /** May be empty: a key that authenticates and can do nothing is a valid start. */
        public Set<String> getScopes() {
            return scopes == null ? Immutables.<String>setOf() : scopes;
        }

        public void setScopes(Set<String> value) {
            this.scopes = value;
        }

        /** Absent means no expiry. */
        public OffsetDateTime getExpiresAt() {
            return expiresAt;
        }

        public void setExpiresAt(OffsetDateTime value) {
            this.expiresAt = value;
        }
    }
}
