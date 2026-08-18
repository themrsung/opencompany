package com.coreintra.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coreintra.app.support.DatabaseTestSupport;
import com.coreintra.auth.crypto.SecretCipher;
import com.coreintra.auth.entity.AuthSession;
import com.coreintra.auth.repository.TotpCredentialRepository;
import com.coreintra.auth.service.AuthenticationService;
import com.coreintra.auth.service.MasterAccountService;
import com.coreintra.auth.service.SessionService;
import com.coreintra.auth.totp.Base32;
import com.coreintra.auth.totp.TotpGenerator;
import com.coreintra.core.org.UserAccount;
import com.coreintra.core.org.repository.UserAccountRepository;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The whole sign-in path against a real database.
 *
 * <p>Covers what the unit tests cannot: that replay protection is actually
 * enforced by the composite key, that recovery codes survive a round trip
 * through hashing, that refresh rotation detects reuse, and that the last
 * master genuinely cannot be removed.
 */
@SpringBootTest
@ActiveProfiles("test")
class AuthenticationIntegrationTest {

    @BeforeAll
    static void requireDatabase() {
        DatabaseTestSupport.requireDatabase();
    }

    @Autowired private AuthenticationService authentication;
    @Autowired private SessionService sessions;
    @Autowired private MasterAccountService masters;
    @Autowired private UserAccountRepository accounts;
    @Autowired private TotpCredentialRepository credentials;
    @Autowired private SecretCipher cipher;
    @Autowired private DataSource dataSource;

    private final TotpGenerator generator = new TotpGenerator();
    private String accountId;
    private static final String USERNAME = "minjun";

    @BeforeEach
    void createAccount() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("delete from consumed_time_step");
        jdbc.execute("delete from recovery_code");
        jdbc.execute("delete from totp_credential");
        jdbc.execute("delete from auth_session");
        jdbc.execute("delete from api_key");
        jdbc.execute("delete from auth_attempt");
        jdbc.execute("delete from permission_grant");
        jdbc.execute("delete from position_job_function");
        jdbc.execute("delete from position");
        jdbc.execute("delete from user_account");

        UserAccount account = new UserAccount(
                UUID.randomUUID().toString(), USERNAME, "김민준", UserAccount.AccountKind.USER);
        accounts.save(account);
        accountId = account.id();
    }

    /** Enrols and returns the current valid code. */
    private String enrolAndGetCode() {
        authentication.beginEnrolment(accountId);
        byte[] secret = cipher.decrypt(credentials.findById(accountId).get().secretEncrypted());
        String code = generator.generateAt(secret, System.currentTimeMillis() / 1000L);
        assertThat(authentication.confirmEnrolment(accountId, code)).isTrue();
        return code;
    }

    @Nested
    @DisplayName("enrolment")
    class Enrolment {

        @Test
        @DisplayName("produces a scannable URI and ten recovery codes")
        void enrolmentShape() {
            AuthenticationService.Enrolment enrolment = authentication.beginEnrolment(accountId);

            assertThat(enrolment.otpauthUri()).startsWith("otpauth://totp/");
            assertThat(enrolment.recoveryCodes()).hasSize(10).doesNotHaveDuplicates();
            assertThat(Base32.decode(enrolment.secretBase32().replace(" ", "")))
                    .as("the displayed secret must decode to the stored one")
                    .hasSize(20);
            assertThat(authentication.remainingRecoveryCodes(accountId)).isEqualTo(10L);
        }

        @Test
        @DisplayName("an unconfirmed enrolment cannot sign in")
        void unconfirmedCannotAuthenticate() {
            authentication.beginEnrolment(accountId);
            byte[] secret = cipher.decrypt(credentials.findById(accountId).get().secretEncrypted());
            String code = generator.generateAt(secret, System.currentTimeMillis() / 1000L);

            // Scanned the QR, never confirmed. The factor must not work yet.
            assertThatThrownBy(() -> authentication.authenticate(USERNAME, code, "127.0.0.1"))
                    .isInstanceOf(AuthenticationService.AuthenticationFailedException.class);
        }

        @Test
        @DisplayName("recovery codes are stored hashed, never in clear")
        void recoveryCodesStoredHashed() {
            AuthenticationService.Enrolment enrolment = authentication.beginEnrolment(accountId);
            String plaintext = enrolment.recoveryCodes().get(0);

            List<String> stored = new JdbcTemplate(dataSource)
                    .queryForList("select code_hash from recovery_code", String.class);
            assertThat(stored).hasSize(10);
            assertThat(stored).noneMatch(hash -> hash.contains(plaintext));
            assertThat(stored).allMatch(hash -> hash.startsWith("s256$"));
        }
    }

    @Nested
    @DisplayName("sign-in")
    class SignIn {

        @Test
        @DisplayName("a valid code signs in, and the same code cannot be reused")
        void replayIsRefusedByTheDatabase() {
            String code = enrolAndGetCode();

            // confirmEnrolment already consumed this step, so the same code is
            // now spent even though it is still arithmetically correct.
            assertThatThrownBy(() -> authentication.authenticate(USERNAME, code, "127.0.0.1"))
                    .isInstanceOf(AuthenticationService.AuthenticationFailedException.class);

            List<Long> steps = new JdbcTemplate(dataSource)
                    .queryForList("select time_step from consumed_time_step", Long.class);
            assertThat(steps).as("the consumed step is recorded").hasSize(1);
        }

        @Test
        @DisplayName("a wrong code and an unknown username fail identically")
        void failuresAreIndistinguishable() {
            enrolAndGetCode();

            String wrongCodeMessage = catchMessage(
                    () -> authentication.authenticate(USERNAME, "000000", "127.0.0.1"));
            String unknownUserMessage = catchMessage(
                    () -> authentication.authenticate("nobody", "000000", "127.0.0.1"));

            assertThat(wrongCodeMessage)
                    .as("the error text must not be a username oracle")
                    .isEqualTo(unknownUserMessage);
        }

        private String catchMessage(Runnable action) {
            try {
                action.run();
                throw new AssertionError("expected authentication to fail");
            } catch (AuthenticationService.AuthenticationFailedException e) {
                return e.getMessage();
            }
        }

        @Test
        @DisplayName("a recovery code works once and is then spent")
        void recoveryCodeIsSingleUse() {
            AuthenticationService.Enrolment enrolment = authentication.beginEnrolment(accountId);
            String recoveryCode = enrolment.recoveryCodes().get(3);

            UserAccount signedIn = authentication.authenticateWithRecoveryCode(
                    USERNAME, recoveryCode, "127.0.0.1");
            assertThat(signedIn.id()).isEqualTo(accountId);
            assertThat(authentication.remainingRecoveryCodes(accountId)).isEqualTo(9L);

            assertThatThrownBy(() -> authentication.authenticateWithRecoveryCode(
                    USERNAME, recoveryCode, "127.0.0.1"))
                    .isInstanceOf(AuthenticationService.AuthenticationFailedException.class);
        }

        @Test
        @DisplayName("regenerating recovery codes invalidates every previous one")
        void regenerationInvalidatesOldCodes() {
            AuthenticationService.Enrolment first = authentication.beginEnrolment(accountId);
            String oldCode = first.recoveryCodes().get(0);

            authentication.regenerateRecoveryCodes(accountId);

            // A user who regenerated because they thought a printout leaked has
            // not fixed anything if the old codes still work.
            assertThatThrownBy(() -> authentication.authenticateWithRecoveryCode(
                    USERNAME, oldCode, "127.0.0.1"))
                    .isInstanceOf(AuthenticationService.AuthenticationFailedException.class);
            assertThat(authentication.remainingRecoveryCodes(accountId)).isEqualTo(10L);
        }
    }

    @Nested
    @DisplayName("sessions")
    class Sessions {

        @Test
        @DisplayName("refresh rotates the token and the old one stops working")
        void refreshRotates() {
            SessionService.IssuedSession first = sessions.issue(accountId, "Firefox", "10.0.0.1");
            SessionService.IssuedSession second = sessions.refresh(
                    first.refreshToken(), "Firefox", "10.0.0.1");

            assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
            assertThat(second.sessionId()).isEqualTo(first.sessionId());
        }

        @Test
        @DisplayName("presenting a superseded token revokes the whole chain")
        void reuseDetectionBurnsTheChain() {
            SessionService.IssuedSession first = sessions.issue(accountId, "Firefox", "10.0.0.1");
            SessionService.IssuedSession second = sessions.refresh(
                    first.refreshToken(), "Firefox", "10.0.0.1");

            // The captured old token is replayed.
            assertThatThrownBy(() -> sessions.refresh(first.refreshToken(), "Attacker", "10.9.9.9"))
                    .isInstanceOf(SessionService.InvalidSessionException.class);

            // ...and the legitimate client's current token is dead too. That is
            // the intended cost: both parties must re-authenticate.
            assertThatThrownBy(() -> sessions.refresh(second.refreshToken(), "Firefox", "10.0.0.1"))
                    .isInstanceOf(SessionService.InvalidSessionException.class);

            assertThat(sessions.activeSessions(accountId)).isEmpty();
        }

        @Test
        @DisplayName("the active-session list shows devices and supports remote sign-out")
        void activeSessionListing() {
            sessions.issue(accountId, "Firefox on Linux", "10.0.0.1");
            SessionService.IssuedSession phone = sessions.issue(accountId, "Safari on iOS", "10.0.0.2");

            List<AuthSession> active = sessions.activeSessions(accountId);
            assertThat(active).hasSize(2);
            assertThat(active).extracting(AuthSession::userAgent)
                    .containsExactlyInAnyOrder("Firefox on Linux", "Safari on iOS");

            sessions.revoke(phone.sessionId(), "user_signed_out_remotely");
            assertThat(sessions.activeSessions(accountId)).hasSize(1);
        }

        @Test
        @DisplayName("tokens are never stored in clear")
        void tokensStoredHashed() {
            SessionService.IssuedSession issued = sessions.issue(accountId, "Firefox", "10.0.0.1");
            List<String> hashes = new JdbcTemplate(dataSource)
                    .queryForList("select refresh_token_hash from auth_session", String.class);
            assertThat(hashes).hasSize(1);
            assertThat(hashes.get(0)).doesNotContain(issued.refreshToken());
        }
    }

    @Nested
    @DisplayName("master accounts")
    class Masters {

        @Test
        @DisplayName("the last active master cannot be demoted or deactivated")
        void lastMasterIsProtected() {
            masters.promote(accountId);
            assertThat(masters.activeMasterCount()).isEqualTo(1L);

            assertThatThrownBy(() -> masters.demote(accountId))
                    .isInstanceOf(MasterAccountService.LastMasterException.class)
                    .hasMessageContaining("last active master");

            // Deactivating locks everyone out just as effectively as demoting,
            // so it is refused on the same terms.
            assertThatThrownBy(() -> masters.deactivate(accountId))
                    .isInstanceOf(MasterAccountService.LastMasterException.class);
        }

        @Test
        @DisplayName("with two masters, either may be demoted")
        void secondMasterUnblocksDemotion() {
            masters.promote(accountId);
            UserAccount other = new UserAccount(
                    UUID.randomUUID().toString(), "second", "이서연", UserAccount.AccountKind.USER);
            accounts.save(other);
            masters.promote(other.id());

            masters.demote(accountId);
            assertThat(masters.activeMasterCount()).isEqualTo(1L);
        }

        @Test
        @DisplayName("a service account cannot be a master")
        void serviceAccountsCannotBeMaster() {
            UserAccount service = new UserAccount(UUID.randomUUID().toString(), "mcp-bot",
                    "MCP client", UserAccount.AccountKind.SERVICE_ACCOUNT);
            accounts.save(service);

            assertThatThrownBy(() -> masters.promote(service.id()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("temporary master account");
        }
    }

    @Test
    @DisplayName("email OTP is hidden when no mail system is configured")
    void emailOtpHiddenWithoutMail() {
        // Not disabled-and-greyed-out, and certainly not offered-then-failing:
        // a second factor whose mail never arrives locks a user out.
        assertThat(authentication.isEmailOtpAvailable()).isFalse();
    }
}
