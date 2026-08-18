package com.coreintra.auth;

import com.coreintra.auth.crypto.SecretCipher;
import com.coreintra.auth.mail.MailSender;
import com.coreintra.auth.mail.SmtpMailSender;
import com.coreintra.auth.mail.UnconfiguredMailSender;
import com.coreintra.auth.repository.AuthAttemptRepository;
import com.coreintra.auth.repository.ConsumedTimeStepRepository;
import com.coreintra.auth.repository.RecoveryCodeRepository;
import com.coreintra.auth.repository.TotpCredentialRepository;
import com.coreintra.auth.service.AuthenticationService;
import com.coreintra.compat.Texts;
import com.coreintra.core.org.repository.UserAccountRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Wires authentication.
 *
 * <p>The mail sender is chosen here from configuration, so every consumer sees
 * one {@link MailSender} bean and asks it whether it is configured, rather than
 * each deciding for itself whether mail exists.
 */
@Configuration
public class AuthConfiguration {

    @Bean
    public SecretCipher secretCipher(
            @Value("${coreintra.security.secret-encryption-key:}") String key) {
        if (Texts.isBlank(key)) {
            // Refusing to start is correct: a default key would encrypt every
            // TOTP secret in every installation under a value that is in the
            // source tree.
            throw new IllegalStateException(
                    "COREINTRA_SECRET_ENCRYPTION_KEY is not set. Generate one with "
                            + "`openssl rand -base64 48`. It encrypts TOTP secrets at rest; "
                            + "losing it means every user must re-enrol, so include it in backups.");
        }
        return new SecretCipher(key);
    }

    /**
     * SMTP when a host is configured, otherwise the unconfigured sender that
     * reports {@code isConfigured() == false} and hides mail-dependent features.
     */
    @Bean
    @Primary
    public MailSender mailSender(
            @Value("${coreintra.mail.host:}") String host,
            @Value("${coreintra.mail.port:587}") int port,
            @Value("${coreintra.mail.username:}") String username,
            @Value("${coreintra.mail.password:}") String password,
            @Value("${coreintra.mail.from:}") String from,
            @Value("${coreintra.mail.starttls:true}") boolean startTls,
            UnconfiguredMailSender fallback) {
        if (Texts.isBlank(host) || Texts.isBlank(from)) {
            return fallback;
        }
        return new SmtpMailSender(host, port, username, password, from, startTls);
    }

    @Bean
    public AuthenticationService authenticationService(UserAccountRepository accounts,
            TotpCredentialRepository credentials, RecoveryCodeRepository recoveryCodes,
            ConsumedTimeStepRepository consumedSteps, AuthAttemptRepository attempts,
            SecretCipher cipher, MailSender mailSender,
            @Value("${coreintra.installation-name:CoreIntra}") String installationName) {
        return new AuthenticationService(accounts, credentials, recoveryCodes, consumedSteps,
                attempts, cipher, mailSender, installationName);
    }
}
