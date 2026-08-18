package com.coreintra.auth.mail;

import org.springframework.stereotype.Component;

/**
 * The default when no SMTP host is configured.
 *
 * <p>Reports itself unconfigured and throws if anyone sends anyway. Throwing
 * rather than silently discarding is deliberate: a mail that vanishes without
 * trace is the failure mode that takes days to diagnose, and any caller
 * reaching here has skipped the {@link MailSender#isConfigured()} check that
 * would have hidden the feature.
 */
@Component
public class UnconfiguredMailSender implements MailSender {

    @Override
    public boolean isConfigured() {
        return false;
    }

    @Override
    public void send(MailMessage message) {
        throw new MailDeliveryException(
                "No mail system is configured for this installation. Set COREINTRA_SMTP_HOST, or "
                        + "check isConfigured() before offering a mail-dependent feature — "
                        + "features that need mail are hidden rather than offered when it is absent.",
                null);
    }
}
