package com.coreintra.runtime.webhook;

import com.coreintra.compat.Texts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.UnsupportedEncodingException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * AES-256-GCM under the installation secret, in the format {@code gcm1$nonce$ciphertext}.
 *
 * <p>The format is the one {@code V3__auth.sql} documents for
 * {@code totp_credential}, on purpose: an installation has one encryption key
 * and one way of using it, and an operator rotating that key should not have to
 * learn two schemes. It is written twice - here and in the auth module's own
 * cipher - because this module may not depend on {@code auth}, and a shared
 * crypto module for eighty lines would be a dependency everything reaches for.
 * If a third caller appears, that is the moment to extract it.
 *
 * <p>The key is required. There is no development fallback: a shipped default
 * would encrypt every installation's secrets under a value that is in the
 * source tree, which is not encryption.
 */
@Service
public class AesGcmWebhookSecretCipher implements WebhookSecretCipher {

    private static final String PREFIX = "gcm1$";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final String base64Key;
    private final SecureRandom random = new SecureRandom();

    public AesGcmWebhookSecretCipher(
            @Value("${coreintra.security.secret-encryption-key:}") String base64Key) {
        this.base64Key = base64Key;
    }

    @Override
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("nothing to encrypt");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(utf8(plaintext));
            return PREFIX + base64(nonce) + "$" + base64(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM is unavailable on this JRE", e);
        }
    }

    @Override
    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            throw new IllegalArgumentException(
                    "a stored webhook secret must be in gcm1$nonce$ciphertext form; this one "
                            + "is not, so the row was written by something else");
        }
        String[] parts = stored.substring(PREFIX.length()).split("\\$");
        if (parts.length != 2) {
            throw new IllegalArgumentException("malformed stored webhook secret");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(),
                    new GCMParameterSpec(TAG_BITS, unbase64(parts[0])));
            return new String(cipher.doFinal(unbase64(parts[1])), "UTF-8");
        } catch (GeneralSecurityException tampered) {
            // GCM authenticates: this is a wrong key or an edited row, never a decode slip.
            throw new IllegalStateException(
                    "a stored webhook secret did not authenticate. Either the installation "
                            + "encryption key changed, or the row was tampered with.", tampered);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 is unavailable on this JRE", e);
        }
    }

    private SecretKeySpec key() {
        if (Texts.isBlank(base64Key)) {
            throw new IllegalStateException(
                    "coreintra.security.secret-encryption-key is not set. Generate one with "
                            + "`openssl rand -base64 48` and keep it in backups: losing it means "
                            + "every webhook subscription must be given a new secret.");
        }
        byte[] material = unbase64(Texts.strip(base64Key));
        // A 48-byte key from the documented recipe; AES-256 wants the first 32.
        return new SecretKeySpec(Arrays.copyOf(material, 32), "AES");
    }

    private static String base64(byte[] bytes) {
        return java.util.Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] unbase64(String text) {
        return java.util.Base64.getDecoder().decode(text);
    }

    private static byte[] utf8(String text) {
        try {
            return text.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 is unavailable on this JRE", e);
        }
    }
}
