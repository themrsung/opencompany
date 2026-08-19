package com.coreintra.auth.service;

import com.coreintra.auth.crypto.SecretCipher;
import com.coreintra.auth.crypto.SecretHasher;
import com.coreintra.auth.entity.AuthAttempt;
import com.coreintra.auth.entity.ConsumedTimeStep;
import com.coreintra.auth.entity.RecoveryCode;
import com.coreintra.auth.entity.TotpCredential;
import com.coreintra.auth.mail.MailSender;
import com.coreintra.auth.repository.AuthAttemptRepository;
import com.coreintra.auth.repository.ConsumedTimeStepRepository;
import com.coreintra.auth.repository.RecoveryCodeRepository;
import com.coreintra.auth.repository.TotpCredentialRepository;
import com.coreintra.auth.totp.Base32;
import com.coreintra.auth.totp.TotpGenerator;
import com.coreintra.auth.totp.TotpVerifier;
import com.coreintra.compat.Immutables;
import com.coreintra.core.org.UserAccount;
import com.coreintra.core.org.repository.UserAccountRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sign-in and enrolment. There are no passwords here or anywhere (ADR 0006).
 *
 * <h2>Failures are indistinguishable to the caller</h2>
 *
 * <p>Unknown username, correct username with a wrong code, a replayed code, an
 * unenrolled account, a deactivated account — all produce the same
 * {@link AuthenticationFailedException} with the same message. The distinction
 * goes to the audit log, never to the client. Otherwise the error text becomes
 * a username oracle, and the throttle becomes an account-existence oracle.
 *
 * <p>For the same reason a failed attempt is recorded against the submitted
 * username even when no such account exists, so a name that does not exist
 * slows down exactly like one that does.
 *
 * <p>Deliberately not a {@code @Service}: it needs the installation name and
 * the chosen {@link MailSender} from configuration, so it is constructed in
 * {@code AuthConfiguration}. Annotating it as well would register the bean
 * twice and fail the context at startup.
 */
public class AuthenticationService {

    /** Deliberately uninformative to the caller. */
    public static class AuthenticationFailedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public AuthenticationFailedException() {
            super("Sign-in failed. Check your username and the current code from your "
                    + "authenticator app.");
        }
    }

    /** Thrown when the throttle is active. Says how long, because that is not a secret. */
    public static class ThrottledException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final Duration retryAfter;

        public ThrottledException(Duration retryAfter) {
            super("Too many failed attempts. Try again in " + retryAfter.getSeconds() + " seconds.");
            this.retryAfter = retryAfter;
        }

        public Duration retryAfter() {
            return retryAfter;
        }
    }

    /** Everything an enrolling user needs, returned exactly once. */
    public static final class Enrolment {
        private final String secretBase32;
        private final String otpauthUri;
        private final List<String> recoveryCodes;

        Enrolment(String secretBase32, String otpauthUri, List<String> recoveryCodes) {
            this.secretBase32 = secretBase32;
            this.otpauthUri = otpauthUri;
            this.recoveryCodes = Immutables.copyOf(recoveryCodes);
        }

        /** Grouped in fours for manual entry. */
        public String secretBase32() {
            return secretBase32;
        }

        /** Rendered as the QR code. Contains the secret; never log it. */
        public String otpauthUri() {
            return otpauthUri;
        }

        /** Plaintext, shown once. Only hashes are stored. */
        public List<String> recoveryCodes() {
            return recoveryCodes;
        }
    }

    private static final int RECOVERY_CODE_COUNT = 10;
    private static final int RECOVERY_CODE_BYTES = 16;

    private final UserAccountRepository accounts;
    private final TotpCredentialRepository credentials;
    private final RecoveryCodeRepository recoveryCodes;
    private final ConsumedTimeStepRepository consumedSteps;
    private final AuthAttemptRepository attempts;
    private final SecretCipher cipher;
    private final TotpGenerator generator;
    private final TotpVerifier verifier;
    private final MailSender mailSender;
    private final String installationName;

    public AuthenticationService(UserAccountRepository accounts,
            TotpCredentialRepository credentials, RecoveryCodeRepository recoveryCodes,
            ConsumedTimeStepRepository consumedSteps, AuthAttemptRepository attempts,
            SecretCipher cipher, MailSender mailSender, String installationName) {
        this.accounts = accounts;
        this.credentials = credentials;
        this.recoveryCodes = recoveryCodes;
        this.consumedSteps = consumedSteps;
        this.attempts = attempts;
        this.cipher = cipher;
        this.generator = new TotpGenerator();
        this.verifier = new TotpVerifier(generator, 1);
        this.mailSender = mailSender;
        this.installationName = installationName;
    }

    /**
     * Whether email OTP may be offered as a second factor.
     *
     * <p>False means the option is <b>hidden</b> in the UI, not shown-and-failing:
     * a second factor whose mail never arrives locks a user out of their own
     * account.
     */
    public boolean isEmailOtpAvailable() {
        return mailSender.isConfigured();
    }

    /**
     * Starts enrolment: new secret, QR URI, and a fresh set of recovery codes.
     *
     * <p>Not usable for sign-in until {@link #confirmEnrolment} succeeds —
     * otherwise a user who scanned the QR and closed the tab would have a
     * working factor they never verified.
     */
    @Transactional
    public Enrolment beginEnrolment(String accountId) {
        UserAccount account = requireAccount(accountId);

        byte[] secret = cipher.newTotpSecret();
        try {
            String encrypted = cipher.encrypt(secret);
            Optional<TotpCredential> existing = credentials.findById(accountId);
            if (existing.isPresent()) {
                existing.get().replaceSecret(encrypted);
                credentials.save(existing.get());
            } else {
                credentials.save(new TotpCredential(accountId, encrypted));
            }

            String uri = generator.enrolmentUri(installationName, account.username(), secret);
            String display = Base32.formatForDisplay(Base32.encode(secret));
            return new Enrolment(display, uri, regenerateRecoveryCodes(accountId));
        } finally {
            // The plaintext secret leaves this method only inside the otpauth
            // URI, which the caller shows once. The array itself is cleared.
            SecretCipher.wipe(secret);
        }
    }

    /** Confirms enrolment by proving possession. */
    @Transactional
    public boolean confirmEnrolment(String accountId, String submittedCode) {
        Optional<TotpCredential> found = credentials.findById(accountId);
        if (!found.isPresent()) {
            return false;
        }
        TotpCredential credential = found.get();
        byte[] secret = cipher.decrypt(credential.secretEncrypted());
        try {
            TotpVerifier.Result result = verifier.verify(accountId, secret, submittedCode,
                    System.currentTimeMillis() / 1000L, new JpaConsumedStepStore());
            if (!result.isSuccess()) {
                return false;
            }
            credential.confirmEnrolment();
            credential.recordUse();
            credentials.save(credential);
            return true;
        } finally {
            SecretCipher.wipe(secret);
        }
    }

    /**
     * Signs in with a TOTP code.
     *
     * @return the authenticated account
     * @throws ThrottledException if too many recent failures
     * @throws AuthenticationFailedException for every other failure, without
     *         distinguishing between them
     */
    @Transactional
    public UserAccount authenticate(String username, String submittedCode, String ipAddress) {
        enforceThrottle(username);

        Optional<UserAccount> found = accounts.findByUsername(username);
        if (!found.isPresent() || !found.get().isActive()) {
            // Recorded against the submitted name so a non-existent username
            // throttles identically to a real one.
            recordAttempt(username, false, "unknown_or_inactive", ipAddress);
            throw new AuthenticationFailedException();
        }
        UserAccount account = found.get();

        Optional<TotpCredential> credential = credentials.findById(account.id());
        if (!credential.isPresent() || !credential.get().isEnrolled()) {
            recordAttempt(username, false, "not_enrolled", ipAddress);
            throw new AuthenticationFailedException();
        }

        byte[] secret = cipher.decrypt(credential.get().secretEncrypted());
        try {
            TotpVerifier.Result result = verifier.verify(account.id(), secret, submittedCode,
                    System.currentTimeMillis() / 1000L, new JpaConsumedStepStore());
            if (!result.isSuccess()) {
                recordAttempt(username, false, result.failure().name().toLowerCase(), ipAddress);
                throw new AuthenticationFailedException();
            }
            credential.get().recordUse();
            credentials.save(credential.get());
            account.touchLastSeen();
            accounts.save(account);
            recordAttempt(username, true, null, ipAddress);
            return account;
        } finally {
            SecretCipher.wipe(secret);
        }
    }

    /**
     * Signs in with a recovery code, consuming it.
     *
     * <p>Throttled on the same counter as TOTP: recovery codes are the fallback
     * path and would otherwise be the unthrottled one.
     */
    @Transactional
    public UserAccount authenticateWithRecoveryCode(String username, String submittedCode,
            String ipAddress) {
        enforceThrottle(username);

        Optional<UserAccount> found = accounts.findByUsername(username);
        if (!found.isPresent() || !found.get().isActive()) {
            recordAttempt(username, false, "unknown_or_inactive", ipAddress);
            throw new AuthenticationFailedException();
        }
        UserAccount account = found.get();

        String cleaned = submittedCode == null ? "" : submittedCode.trim().replace(" ", "");
        for (RecoveryCode candidate : recoveryCodes.findByAccountIdAndUsedAtIsNull(account.id())) {
            if (SecretHasher.matches(cleaned, candidate.codeHash())) {
                candidate.markUsed();
                recoveryCodes.save(candidate);
                recordAttempt(username, true, null, ipAddress);
                account.touchLastSeen();
                accounts.save(account);
                return account;
            }
        }
        recordAttempt(username, false, "recovery_code_invalid", ipAddress);
        throw new AuthenticationFailedException();
    }

    /** Unused recovery codes remaining. Surfaced in the UI so nobody runs out unawares. */
    @Transactional(readOnly = true)
    public long remainingRecoveryCodes(String accountId) {
        return recoveryCodes.countByAccountIdAndUsedAtIsNull(accountId);
    }

    /**
     * Issues a fresh set, invalidating every previous code.
     *
     * <p>All-or-nothing on purpose: leaving old codes valid alongside new ones
     * would mean a user who regenerated because they believed a printout was
     * compromised had not actually fixed anything.
     */
    @Transactional
    public List<String> regenerateRecoveryCodes(String accountId) {
        recoveryCodes.deleteByAccountId(accountId);
        List<String> plaintext = new ArrayList<String>(RECOVERY_CODE_COUNT);
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            String code = SecretHasher.randomToken(RECOVERY_CODE_BYTES);
            plaintext.add(code);
            recoveryCodes.save(new RecoveryCode(
                    UUID.randomUUID().toString(), accountId, SecretHasher.hash(code)));
        }
        return plaintext;
    }

    private void enforceThrottle(String username) {
        OffsetDateTime since = OffsetDateTime.now().minus(LockoutPolicy.WINDOW);
        List<AuthAttempt> failures = attempts.findRecentFailures(username, since);
        if (failures.isEmpty()) {
            return;
        }
        Duration sinceLast = Duration.between(failures.get(0).attemptedAt(), OffsetDateTime.now());
        if (LockoutPolicy.isThrottled(failures.size(), sinceLast)) {
            Duration required = LockoutPolicy.delayAfter(failures.size());
            throw new ThrottledException(required.minus(sinceLast));
        }
    }

    private void recordAttempt(String username, boolean succeeded, String reason, String ipAddress) {
        AuthAttempt attempt = new AuthAttempt(
                UUID.randomUUID().toString(), username, succeeded, reason);
        attempt.setIpAddress(ipAddress);
        attempts.save(attempt);
    }

    private UserAccount requireAccount(String accountId) {
        Optional<UserAccount> found = accounts.findById(accountId);
        if (!found.isPresent()) {
            throw new IllegalArgumentException("no such account: " + accountId);
        }
        return found.get();
    }

    /**
     * Replay store backed by the composite primary key.
     *
     * <p>Atomicity comes from the database, not from this class: a duplicate
     * insert violates the key and the transaction fails, which is the correct
     * outcome for two concurrent presentations of one code.
     */
    private final class JpaConsumedStepStore implements TotpVerifier.ConsumedStepStore {

        @Override
        public boolean isConsumed(String accountId, long timeStep) {
            return consumedSteps.existsById(new ConsumedTimeStep.Key(accountId, timeStep));
        }

        @Override
        public void consume(String accountId, long timeStep) {
            consumedSteps.save(new ConsumedTimeStep(accountId, timeStep));
        }
    }
}
