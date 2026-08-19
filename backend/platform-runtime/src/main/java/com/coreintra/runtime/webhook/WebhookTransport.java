package com.coreintra.runtime.webhook;

import java.io.Serializable;

/**
 * The one thing about a delivery that leaves this process.
 *
 * <p>It is an interface so the retry policy can be tested without a socket. A
 * dispatcher whose backoff can only be exercised against a real endpoint is a
 * dispatcher whose backoff is never exercised.
 */
public interface WebhookTransport {

    /**
     * POSTs one delivery. Never throws for a failed delivery: a refused
     * connection and a 500 are the same kind of event to the caller, and the
     * difference belongs in {@link Result#error()}.
     */
    Result post(String url, String payload, String signatureHeader);

    /** What the far end said, or why it said nothing. */
    final class Result implements Serializable {

        private static final long serialVersionUID = 1L;

        private final Integer statusCode;
        private final String error;

        private Result(Integer statusCode, String error) {
            this.statusCode = statusCode;
            this.error = error;
        }

        public static Result of(int statusCode) {
            return new Result(Integer.valueOf(statusCode), null);
        }

        /** No answer at all: refused, timed out, unresolvable. */
        public static Result failed(String error) {
            return new Result(null, error);
        }

        public Integer statusCode() {
            return statusCode;
        }

        public String error() {
            return error;
        }

        /**
         * 2xx is delivered; everything else is not.
         *
         * <p>A 3xx is not followed. A redirect on a webhook endpoint is either a
         * misconfiguration or someone else's endpoint, and following it would
         * send a signed payload somewhere the subscriber never named.
         */
        public boolean isDelivered() {
            return statusCode != null && statusCode.intValue() >= 200 && statusCode.intValue() < 300;
        }
    }
}
