package com.coreintra.runtime.webhook;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Turns the dispatcher into something that actually runs.
 *
 * <p>Deliberately a separate class from {@link WebhookService}: the service is
 * the behaviour and is unit-testable by calling it, and this is the timer. It
 * can be switched off with {@code coreintra.webhooks.dispatcher.enabled=false},
 * which is what a second box would do to leave one dispatcher running.
 *
 * <p>{@code fixedDelay}, not {@code fixedRate}: a pass that takes longer than
 * the interval must not have the next one start on top of it. With one thread
 * and a fixed delay, passes cannot overlap at all, which is what makes running
 * this in the application process safe on one box.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "coreintra.webhooks.dispatcher.enabled",
        havingValue = "true", matchIfMissing = true)
public class WebhookDispatchScheduler {

    private final WebhookService webhooks;

    public WebhookDispatchScheduler(WebhookService webhooks) {
        this.webhooks = webhooks;
    }

    /**
     * Relay first, then send.
     *
     * <p>In that order so an event committed a moment ago is fanned out and
     * sent in the same pass rather than waiting for the next one. Both halves
     * are restartable, so a pass that dies between them loses nothing.
     */
    @Scheduled(
            fixedDelayString = "${coreintra.webhooks.dispatcher.interval-ms:5000}",
            initialDelayString = "${coreintra.webhooks.dispatcher.initial-delay-ms:20000}")
    public void relayAndDispatch() {
        webhooks.relayOutbox();
        webhooks.dispatchDue();
    }
}
