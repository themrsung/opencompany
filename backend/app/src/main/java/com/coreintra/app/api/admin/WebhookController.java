package com.coreintra.app.api.admin;

import com.coreintra.app.api.permission.CurrentPrincipal;
import com.coreintra.compat.Immutables;
import com.coreintra.core.permission.PermissionEvaluator;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionTarget;
import com.coreintra.runtime.webhook.WebhookDeliveryRow;
import com.coreintra.runtime.webhook.WebhookService;
import com.coreintra.runtime.webhook.WebhookSubscriptionRow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Size;
import org.springframework.http.HttpStatus;
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
 * Webhook subscriptions, so a client system can react to what happens here
 * without polling.
 *
 * <p>The secret is supplied by the subscriber and is never returned. Only its
 * prefix comes back, which is enough to tell two subscriptions apart in a log
 * and useless to anyone who intercepts the response. A subscription cannot be
 * created without one: an unsigned webhook is one the receiver has no reason to
 * believe, and offering it would be offering a footgun with a default.
 *
 * <p>Delivery history is exposed because the first question after "did it
 * arrive?" is always "what did you get back?", and without it the answer is a
 * support ticket and a log grep on a box the caller cannot reach.
 */
@RestController
@RequestMapping("/api/v1/admin/webhooks")
@Tag(name = "Webhooks",
        description = "Signed, retried with backoff, and dead-lettered rather than retried "
                + "forever.")
public class WebhookController {

    private static final PermissionKey READ = PermissionKey.parse("admin.webhook:read");
    private static final PermissionKey MANAGE = PermissionKey.parse("admin.webhook:manage");

    private final WebhookService webhooks;
    private final PermissionEvaluator evaluator;
    private final CurrentPrincipal current;

    public WebhookController(WebhookService webhooks, PermissionEvaluator evaluator,
            CurrentPrincipal current) {
        this.webhooks = webhooks;
        this.evaluator = evaluator;
        this.current = current;
    }

    public static class SubscribeRequest {
        @NotBlank
        private String companyId;
        @NotBlank
        private String url;
        @NotBlank
        @Size(min = 32, message = "A webhook secret shorter than 32 characters is not worth "
                + "signing with. Generate one with `openssl rand -base64 48`.")
        private String secret;
        private String description;
        @NotEmpty(message = "Name the events you want. A subscription to nothing is a "
                + "subscription nobody will notice is broken.")
        private List<String> eventTypes;

        public String getCompanyId() {
            return companyId;
        }

        public void setCompanyId(String value) {
            this.companyId = value;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String value) {
            this.url = value;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String value) {
            this.secret = value;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String value) {
            this.description = value;
        }

        public List<String> getEventTypes() {
            return eventTypes;
        }

        public void setEventTypes(List<String> value) {
            this.eventTypes = value;
        }
    }

    /** A subscription as it can safely be read back. Never carries the secret. */
    public static class Subscription {
        private final String id;
        private final String url;
        private final String secretPrefix;
        private final String description;
        private final List<String> eventTypes;
        private final boolean active;

        Subscription(WebhookSubscriptionRow row) {
            this.id = row.id();
            this.url = row.url();
            this.secretPrefix = row.secretPrefix();
            this.description = row.description();
            this.eventTypes = Immutables.copyOf(new ArrayList<String>(row.eventTypes()));
            this.active = row.isActive();
        }

        public String getId() {
            return id;
        }

        public String getUrl() {
            return url;
        }

        /** Enough to identify the secret in a log; useless to anyone who reads it. */
        public String getSecretPrefix() {
            return secretPrefix;
        }

        public String getDescription() {
            return description;
        }

        public List<String> getEventTypes() {
            return eventTypes;
        }

        public boolean isActive() {
            return active;
        }
    }

    public static class Delivery {
        private final String id;
        private final String eventType;
        private final String state;
        private final int attempts;
        private final Integer lastResponseStatus;
        private final String lastError;
        private final String nextAttemptAt;

        Delivery(WebhookDeliveryRow row) {
            this.id = row.id();
            this.eventType = row.eventType();
            this.state = row.state();
            this.attempts = row.attempts();
            this.lastResponseStatus = row.lastStatusCode();
            this.lastError = row.lastError();
            this.nextAttemptAt = row.nextAttemptAt() == null
                    ? null : String.valueOf(row.nextAttemptAt());
        }

        public String getId() {
            return id;
        }

        public String getEventType() {
            return eventType;
        }

        public String getState() {
            return state;
        }

        public int getAttempts() {
            return attempts;
        }

        public Integer getLastResponseStatus() {
            return lastResponseStatus;
        }

        public String getLastError() {
            return lastError;
        }

        public String getNextAttemptAt() {
            return nextAttemptAt;
        }
    }

    @GetMapping
    @Operation(summary = "Subscriptions for a company")
    public List<Subscription> list(@RequestParam String companyId) {
        require(READ, companyId, "webhook subscriptions");
        List<Subscription> found = new ArrayList<Subscription>();
        for (WebhookSubscriptionRow row : webhooks.subscriptionsFor(companyId)) {
            found.add(new Subscription(row));
        }
        return Immutables.copyOf(found);
    }

    @PostMapping
    @Operation(summary = "Subscribe to events",
            description = "The secret is stored encrypted and never returned. Keep your copy.")
    public ResponseEntity<Subscription> subscribe(@Valid @RequestBody SubscribeRequest body) {
        PermissionPrincipal principal = require(MANAGE, body.getCompanyId(), "webhook subscription");
        Set<String> events = new LinkedHashSet<String>(body.getEventTypes());
        WebhookSubscriptionRow row = webhooks.subscribe(body.getCompanyId(), body.getUrl(),
                body.getSecret(), body.getDescription(), events, principal.accountId());
        return ResponseEntity.status(HttpStatus.CREATED).body(new Subscription(row));
    }

    @DeleteMapping("/{subscriptionId}")
    @Operation(summary = "Retire a subscription",
            description = "Retired, not deleted: the deliveries already attempted under it stay "
                    + "readable, which is the whole reason anyone looks at this screen.")
    public ResponseEntity<Void> retire(@PathVariable String subscriptionId,
            @RequestParam String companyId) {
        require(MANAGE, companyId, "webhook subscription");
        webhooks.retire(subscriptionId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{subscriptionId}/deliveries")
    @Operation(summary = "Recent deliveries and what the endpoint said back")
    public List<Delivery> deliveries(@PathVariable String subscriptionId,
            @RequestParam String companyId,
            @RequestParam(defaultValue = "50") int limit) {
        require(READ, companyId, "webhook deliveries");
        List<Delivery> found = new ArrayList<Delivery>();
        for (WebhookDeliveryRow row : webhooks.recentDeliveries(subscriptionId,
                Math.min(Math.max(limit, 1), 200))) {
            found.add(new Delivery(row));
        }
        return Immutables.copyOf(found);
    }

    private PermissionPrincipal require(PermissionKey key, String companyId, String what) {
        PermissionPrincipal principal = current.require();
        evaluator.check(principal, key, PermissionTarget.builder()
                .companyId(companyId)
                .asOfBusinessDate(LocalDate.now())
                .description(what)
                .build()).orThrow();
        return principal;
    }
}
