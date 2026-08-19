package com.coreintra.auth.mail;

/**
 * Sending mail, if this installation has any.
 *
 * <p>Many on-prem boxes have no outbound mail at all, and that is a supported
 * configuration rather than a broken one. Everything that might send mail asks
 * {@link #isConfigured()} first, and features that depend on it — email OTP as
 * a second factor, approval notifications by mail — are <b>hidden</b> when it
 * returns false.
 *
 * <p>Hidden, not disabled-and-greyed-out, and certainly not offered-then-failing.
 * A second factor that silently never arrives locks a user out of their own
 * account, which is a worse outcome than not offering it.
 */
public interface MailSender {

    /** False when no mail system is wired. Callers must check before offering mail features. */
    boolean isConfigured();

    /**
     * @throws MailDeliveryException on failure; callers must not treat mail as
     *         guaranteed, particularly in an authentication flow
     */
    void send(MailMessage message);

    /** Thrown when delivery fails. Never carries the message body. */
    class MailDeliveryException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public MailDeliveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
