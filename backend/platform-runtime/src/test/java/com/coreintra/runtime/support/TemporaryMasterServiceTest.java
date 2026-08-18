package com.coreintra.runtime.support;

import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.runtime.testing.InMemoryTemporaryMasterGrants;
import com.coreintra.runtime.testing.InMemoryTemporaryMasterSwitch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * §8 and the §13 acceptance test, at the persistence boundary.
 *
 * <p>The equivalent assertions exist against the domain object in {@code auth}.
 * They are repeated here because this module cannot depend on that one, and
 * because a row can reach this table by a route the domain never saw.
 */
class TemporaryMasterServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-18T09:00:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final OffsetDateTime ISSUED = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final BusinessInstant ISSUED_ON =
            BusinessInstant.of(LocalDate.of(2026, 8, 18), 9, 0, 0);

    private final InMemoryTemporaryMasterGrants grants = new InMemoryTemporaryMasterGrants();
    private final InMemoryTemporaryMasterSwitch switches = new InMemoryTemporaryMasterSwitch();
    private final TemporaryMasterService service =
            new TemporaryMasterService(grants, switches, FIXED, false);

    @Test
    @DisplayName("a temporary master with every capability off can read nothing")
    void nothingTickedReadsNothing() {
        TemporaryMasterGrantRow grant = service.issue(issuance().build());

        assertThat(grant.capabilities()).isEmpty();
        assertThat(grant.isActiveAt(ISSUED.plusMinutes(1)))
                .as("the session is live; it simply may not do anything")
                .isTrue();

        String[] everythingSomeoneMightTry = {
                "hr.employee:read",
                "hr.employee:export",
                "hr.compensation:read",
                "approval.document:read",
                "accounting.entry:read",
                "attendance.record:read",
                "company.settings:read",
        };
        for (String capability : everythingSomeoneMightTry) {
            assertThat(service.allowsAt(grant.accountId(), capability, ISSUED.plusMinutes(1)))
                    .as("a support session with nothing ticked must not be able to read " + capability)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("a ticked capability is allowed, and only that one")
    void onlyWhatWasTicked() {
        TemporaryMasterGrantRow grant = service.issue(
                issuance().capability("approval.document:read").build());
        OffsetDateTime during = ISSUED.plusMinutes(30);

        assertThat(service.allowsAt(grant.accountId(), "approval.document:read", during)).isTrue();
        assertThat(service.allowsAt(grant.accountId(), "approval.document:update", during)).isFalse();
        assertThat(service.allowsAt(grant.accountId(), "hr.employee:read", during)).isFalse();
    }

    @Test
    @DisplayName("a time to live over 24 hours is refused")
    void twentyFourHourCeiling() {
        assertThatThrownBy(() -> issuance().timeToLive(Duration.ofHours(25)).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 24 hours");

        assertThat(issuance().timeToLive(Duration.ofHours(24)).build().timeToLive())
                .as("24 hours exactly is the ceiling, not one second past it")
                .isEqualTo(Duration.ofHours(24));
    }

    @Test
    @DisplayName("there is no method anywhere that extends a session")
    void noExtension() {
        assertThat(publicMethodNamesOf(TemporaryMasterGrantRow.class))
                .noneMatch(TemporaryMasterServiceTest::soundsLikeAnExtension);
        assertThat(publicMethodNamesOf(TemporaryMasterService.class))
                .noneMatch(TemporaryMasterServiceTest::soundsLikeAnExtension);
        assertThat(publicMethodNamesOf(TemporaryMasterGrantRow.class))
                .as("expiresAt is written once at issue")
                .doesNotContain("setExpiresAt", "setTimeToLive");
    }

    @Test
    @DisplayName("the deadline is enforced without anything having to sweep it")
    void expiryIsCheckedNotSwept() {
        TemporaryMasterGrantRow grant = service.issue(
                issuance().timeToLive(Duration.ofHours(4)).capability("hr.employee:read").build());

        assertThat(service.allowsAt(grant.accountId(), "hr.employee:read", ISSUED.plusHours(3)))
                .isTrue();
        assertThat(service.allowsAt(grant.accountId(), "hr.employee:read", ISSUED.plusHours(4)))
                .as("no revocation job has run; the deadline alone must be enough")
                .isFalse();
    }

    @Test
    @DisplayName("revoking ends access immediately, and keeps who ended it")
    void revocationIsImmediate() {
        TemporaryMasterGrantRow grant = service.issue(
                issuance().capability("hr.employee:read").build());
        service.revoke(grant.id(), "master-1");

        assertThat(service.allowsAt(grant.accountId(), "hr.employee:read", ISSUED.plusMinutes(1)))
                .isFalse();
        assertThat(grants.stored(grant.id()).revokedByAccountId()).isEqualTo("master-1");
    }

    @Test
    @DisplayName("a second revocation does not overwrite who ended the session")
    void revocationIsIdempotent() {
        TemporaryMasterGrantRow grant = service.issue(issuance().build());
        service.revoke(grant.id(), "master-1");
        service.revoke(grant.id(), "master-2");

        assertThat(grants.stored(grant.id()).revokedByAccountId()).isEqualTo("master-1");
    }

    @Test
    @DisplayName("a wildcard capability is refused: there is no grant-all")
    void noGrantAll() {
        assertThatThrownBy(() -> issuance().capability("hr.*"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no grant-all");
    }

    @Test
    @DisplayName("the capabilities that would let a session escape itself are never grantable")
    void neverGrantable() {
        for (String forbidden : TemporaryMasterCapabilities.NEVER_GRANTABLE) {
            assertThatThrownBy(() -> issuance().capability(forbidden))
                    .as(forbidden + " must be refused")
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(TemporaryMasterCapabilities.NEVER_GRANTABLE)
                .as("the list is duplicated in auth and in V9; changing it is deliberate")
                .containsExactlyInAnyOrder(
                        "admin.permission:grant",
                        "admin.permission:revoke",
                        "admin.master:create",
                        "admin.master:update",
                        "admin.master:delete",
                        "admin.temporaryMaster:issue",
                        "hr.employmentRules:amend",
                        "hr.employmentRules:create",
                        "hr.employmentRules:repeal",
                        "company.representation:update",
                        "admin.audit:disable",
                        "admin.audit:delete");
    }

    @Test
    @DisplayName("a joint quorum is not met by fewer named representatives than it requires")
    void jointQuorumCountsPeople() {
        assertThatThrownBy(() -> issuance()
                .approval("doc-1", TemporaryMasterIssuance.MODE_JOINT, 2)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires 2");
    }

    @Test
    @DisplayName("typing the wrong company name does not confirm an issuance")
    void confirmationIsNotACheckbox() {
        assertThatThrownBy(() -> issuance().typedCompanyName("에크미").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("typed exactly");
    }

    @Test
    @DisplayName("issuance without a reason is refused; the banner has nothing to say otherwise")
    void reasonIsMandatory() {
        assertThatThrownBy(() -> issuance().reason("   ").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs a reason");
    }

    @Test
    @DisplayName("once the kill switch is thrown, no session can be issued and it cannot be undone")
    void killSwitchIsOneWay() {
        service.disableIssuanceForInstallation("master-1", "정책상 원격 지원을 사용하지 않습니다.");

        assertThat(service.issuanceDisabled()).isTrue();
        assertThatThrownBy(() -> service.issue(issuance().build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("permanently disabled");
        assertThat(publicMethodNamesOf(TemporaryMasterService.class))
                .as("there is no counterpart that turns it back on")
                .doesNotContain("enableIssuanceForInstallation");
        assertThat(publicMethodNamesOf(TemporaryMasterSwitchRow.class))
                .noneMatch(name -> name.toLowerCase(Locale.ROOT).startsWith("enable"));
    }

    @Test
    @DisplayName("configuration can refuse issuance for this boot without touching the switch")
    void configurationCanRefuseToo() {
        TemporaryMasterService offForNow =
                new TemporaryMasterService(grants, switches, FIXED, true);

        assertThatThrownBy(() -> offForNow.issue(issuance().build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("configuration");
        assertThat(service.issuanceDisabled())
                .as("the permanent switch is a different thing and was not thrown")
                .isFalse();
    }

    @Test
    @DisplayName("a live session is listed for the banner every user sees")
    void listsLiveSessions() {
        service.issue(issuance().build());

        assertThat(service.activeSessions("c1")).hasSize(1);
    }

    private static TemporaryMasterIssuance.Builder issuance() {
        return TemporaryMasterIssuance.builder()
                .company("c1", "주식회사 에크미")
                .typedCompanyName("주식회사 에크미")
                .accountId("support-1")
                .issuedByAccountId("master-1")
                .engineerName("Jane Vendor")
                .reason("급여 내보내기가 실패하는 원인을 확인합니다.")
                .ticketReference("SUP-4821")
                .issuedAt(ISSUED)
                .issuedOn(ISSUED_ON)
                .approval("doc-1", TemporaryMasterIssuance.MODE_SEVERAL, 1)
                .approvedBy("rep-1", "김대표");
    }

    private static boolean soundsLikeAnExtension(String methodName) {
        String lower = methodName.toLowerCase(Locale.ROOT);
        return lower.contains("extend") || lower.contains("renew") || lower.contains("prolong")
                || lower.contains("postpone");
    }

    private static List<String> publicMethodNamesOf(Class<?> type) {
        List<String> names = new ArrayList<String>();
        for (Method method : type.getMethods()) {
            if (method.getDeclaringClass() != Object.class) {
                names.add(method.getName());
            }
        }
        return names;
    }
}
