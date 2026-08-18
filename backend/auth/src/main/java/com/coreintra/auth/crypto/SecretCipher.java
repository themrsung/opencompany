package com.coreintra.auth.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Authenticated encryption for secrets that must be recoverable.
 *
 * <p>A TOTP secret cannot be hashed: the server has to compute codes from it,
 * so it must come back out. That makes it the one credential in this system
 * stored reversibly, and it is encrypted with AES-256-GCM under a key supplied
 * by configuration and never written to the database.
 *
 * <p>The practical consequence, which belongs in the runbook rather than
 * hidden here: <b>losing the encryption key means every user must re-enrol</b>.
 * It does not mean an attacker with a database dump can sign in — that is the
 * point — but it is not a recoverable situation, so the key is part of
 * {@code make backup} and of the deployment's reproducibility contract.
 *
 * <p>GCM rather than CBC because it authenticates: a tampered ciphertext fails
 * to decrypt rather than yielding plausible garbage that then gets used as a
 * TOTP secret.
 */
public final class SecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int NONCE_BYTES = 12;
    private static final String PREFIX = "gcm1";

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    /**
     * @param keyMaterial configured key of any length; hashed to exactly 256 bits
     *                    so an operator's base64 string of awkward length still
     *                    produces a valid AES key rather than an exception at
     *                    first sign-in
     */
    public SecretCipher(String keyMaterial) {
        if (keyMaterial == null || keyMaterial.trim().length() < 32) {
            throw new IllegalArgumentException(
                    "the secret encryption key must be at least 32 characters. Generate one with "
                            + "`openssl rand -base64 48` and set COREINTRA_SECRET_ENCRYPTION_KEY. "
                            + "Losing it means every user must re-enrol their authenticator.");
        }
        this.key = new SecretKeySpec(sha256(keyMaterial), "AES");
    }

    /** @return {@code gcm1$<base64 nonce>$<base64 ciphertext+tag>} */
    public String encrypt(byte[] plaintext) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext);
            return PREFIX + "$" + encode(nonce) + "$" + encode(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("failed to encrypt secret", e);
        }
    }

    /**
     * @throws IllegalStateException if the value was tampered with or the key
     *         has changed — deliberately loud, because silently returning
     *         garbage here would produce codes that never match and an
     *         unexplainable sign-in failure
     */
    public byte[] decrypt(String stored) {
        String[] parts = stored == null ? new String[0] : stored.split("\\$");
        if (parts.length != 3 || !PREFIX.equals(parts[0])) {
            throw new IllegalStateException("stored secret is not in the expected gcm1 format");
        }
        try {
            byte[] nonce = Base64.getDecoder().decode(parts[1]);
            byte[] ciphertext = Base64.getDecoder().decode(parts[2]);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(
                    "failed to decrypt a stored secret. Either the value was tampered with, or "
                            + "COREINTRA_SECRET_ENCRYPTION_KEY has changed since it was written. "
                            + "A changed key cannot be recovered from; affected users must re-enrol.",
                    e);
        }
    }

    /** Fresh TOTP secret material. 20 bytes is RFC 4226's recommended minimum. */
    public byte[] newTotpSecret() {
        byte[] secret = new byte[20];
        random.nextBytes(secret);
        return secret;
    }

    /** Overwrites secret material once it is no longer needed. */
    public static void wipe(byte[] secret) {
        if (secret != null) {
            Arrays.fill(secret, (byte) 0);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable in this JRE", e);
        }
    }

    private static String encode(byte[] data) {
        return Base64.getEncoder().withoutPadding().encodeToString(data);
    }
}
