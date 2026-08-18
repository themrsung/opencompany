package com.coreintra.runtime.webhook;

/**
 * Turns a subscription secret into ciphertext and back.
 *
 * <p>It is an interface because the storage format is a deployment concern and
 * because a test has no business holding a real installation key. It exists at
 * all because signing needs the secret in the clear at the moment of delivery:
 * unlike a password, a webhook secret cannot be hashed.
 */
public interface WebhookSecretCipher {

    /** @return the stored form, safe to write to {@code webhook_subscription.secret_encrypted} */
    String encrypt(String plaintext);

    /** @return the signing key, from a stored form produced by {@link #encrypt} */
    String decrypt(String stored);
}
