package com.coreintra.runtime;

import com.coreintra.runtime.webhook.Backoff;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/**
 * The beans this module needs that nobody else declares.
 *
 * <p>Services here take a {@link Clock} rather than calling {@code now()},
 * because a deadline, a backoff and an expiry are all things a test has to be
 * able to move. UTC, not the system zone: this clock stamps
 * {@code created_at} - what the machine observed - and business time is a
 * separate value the caller supplies (ADR 0002).
 *
 * <p>{@code @ConditionalOnMissingBean} so that an application that already has
 * a clock keeps it, and so that adding one later is not a conflict.
 */
@Configuration
public class PlatformRuntimeConfiguration {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock platformClock() {
        return Clock.systemUTC();
    }

    /**
     * The webhook retry policy, as configuration rather than a constant.
     *
     * <p>An installation whose clients are all on the same LAN wants a shorter
     * ceiling than one delivering over the public internet. The attempt limit
     * stays bounded whatever is configured - {@link Backoff} refuses an
     * unbounded policy, and {@code webhook_delivery} carries a matching CHECK.
     */
    @Bean
    @ConditionalOnMissingBean(Backoff.class)
    public Backoff webhookBackoff(
            @Value("${coreintra.webhooks.retry.base-seconds:30}") long baseSeconds,
            @Value("${coreintra.webhooks.retry.cap-seconds:1800}") long capSeconds,
            @Value("${coreintra.webhooks.retry.max-attempts:8}") int maxAttempts) {
        return new Backoff(Duration.ofSeconds(baseSeconds), Duration.ofSeconds(capSeconds),
                maxAttempts);
    }
}
