package com.coreintra.runtime.webhook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignerTest {

    private static final String SECRET = "whsec_3f0c9d1e8a4b47c2";
    private static final String PAYLOAD =
            "{\"type\":\"approval.approved\",\"documentId\":\"d-1\",\"amount\":\"1400000.25\"}";
    private static final OffsetDateTime SENT =
            OffsetDateTime.of(2026, 8, 18, 9, 0, 0, 0, ZoneOffset.UTC);

    @Test
    @DisplayName("a signature made with the secret verifies with the secret")
    void roundTrip() {
        String header = WebhookSigner.sign(SECRET, PAYLOAD, SENT);

        assertThat(header).startsWith("t=").contains(",v1=");
        assertThat(WebhookSigner.verify(SECRET, PAYLOAD, header, SENT)).isTrue();
    }

    @Test
    @DisplayName("a tampered body fails verification")
    void tamperedBodyFails() {
        String header = WebhookSigner.sign(SECRET, PAYLOAD, SENT);
        String tampered = PAYLOAD.replace("1400000.25", "14000000.25");

        assertThat(WebhookSigner.verify(SECRET, tampered, header, SENT))
                .as("one added digit in an amount is the whole reason webhooks are signed")
                .isFalse();
    }

    @Test
    @DisplayName("another subscriber's secret does not verify our payload")
    void wrongSecretFails() {
        String header = WebhookSigner.sign(SECRET, PAYLOAD, SENT);

        assertThat(WebhookSigner.verify("whsec_someone_else", PAYLOAD, header, SENT)).isFalse();
    }

    @Test
    @DisplayName("a correctly signed delivery replayed later is refused")
    void staleSignatureFails() {
        String header = WebhookSigner.sign(SECRET, PAYLOAD, SENT);

        assertThat(WebhookSigner.verify(SECRET, PAYLOAD, header, SENT.plusMinutes(4))).isTrue();
        assertThat(WebhookSigner.verify(SECRET, PAYLOAD, header, SENT.plusMinutes(6)))
                .as("the timestamp is inside the signature so a capture goes stale")
                .isFalse();
        assertThat(WebhookSigner.verify(SECRET, PAYLOAD, header, SENT.plusHours(3),
                Duration.ofHours(4)))
                .as("a receiver may choose a wider window, and then it verifies")
                .isTrue();
    }

    @Test
    @DisplayName("a header that is missing a part, or is not ours, fails rather than throwing")
    void malformedHeaderFails() {
        assertThat(WebhookSigner.verify(SECRET, PAYLOAD, "", SENT)).isFalse();
        assertThat(WebhookSigner.verify(SECRET, PAYLOAD, "v1=deadbeef", SENT)).isFalse();
        assertThat(WebhookSigner.verify(SECRET, PAYLOAD, "t=notanumber,v1=deadbeef", SENT)).isFalse();
        assertThat(WebhookSigner.verify(SECRET, PAYLOAD, "t=" + SENT.toEpochSecond(), SENT)).isFalse();
    }

    @Test
    @DisplayName("moving the payload between the timestamp and the body cannot forge a signature")
    void separatorIsNotAmbiguous() {
        OffsetDateTime at = SENT;
        String honest = WebhookSigner.signature(SECRET, "abc", at.toEpochSecond());
        String shifted = WebhookSigner.signature(SECRET, ".abc", at.toEpochSecond());

        assertThat(honest).isNotEqualTo(shifted);
    }
}
