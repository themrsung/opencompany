package com.coreintra.auth.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coreintra.approval.domain.ApprovalState;
import com.coreintra.approval.domain.RepresentationMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Acceptance tests for the temporary master account.
 *
 * <p><b>Written before the implementation</b>, as the brief requires. This is
 * the highest-risk feature in the system: it exists so a vendor support engineer
 * can help without being handed standing god-rights, and every one of these
 * properties is what keeps that bargain honest.
 *
 * <p>The tests are phrased as the guarantees a client is being asked to trust,
 * not as descriptions of the code, so that a future refactor has to keep the
 * promises rather than keep the shape.
 */
class TemporaryMasterAcceptanceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-30T09:00:00Z");
    private static final String COMPANY = "에이스전자";

    private static TemporaryMasterGrant.Builder validRequest() {
        return TemporaryMasterGrant.builder()
                .id("tm-1")
                .companyId("c-1")
                .companyName(COMPANY)
                .issuedByAccountId("acc-master")
                .engineerName("지원 엔지니어")
                .reason("결재선 설정 오류 조사 (INC-4821)")
                .ticketReference("INC-4821")
                .issuedAt(NOW)
                .timeToLive(Duration.ofHours(4))
                .typedCompanyNameConfirmation(COMPANY)
                .approval("doc-approve-1", ApprovalState.APPROVED,
                        RepresentationMode.several(1), Arrays.asList("rep-1"));
    }

    @Nested
    @DisplayName("every capability defaults to off")
    class DefaultsOff {

        /** ACCEPTANCE: a temporary master with all capabilities off can read nothing. */
        @Test
        @DisplayName("a grant with nothing ticked can read nothing at all")
        void nothingTickedReadsNothing() {
            TemporaryMasterGrant grant = validRequest().build();

            assertThat(grant.capabilities()).isEmpty();
            assertThat(grant.allows("hr.employee:read")).isFalse();
            assertThat(grant.allows("approval.document:read")).isFalse();
            assertThat(grant.allows("accounting.entry:read")).isFalse();
            // Not even the most innocuous read. "Defaults to off" has to mean
            // off, or the default is a lie the client only discovers in an audit.
            assertThat(grant.allows("company.settings:read")).isFalse();
        }

        @Test
        @DisplayName("a ticked capability grants exactly itself, and nothing adjacent")
        void tickingIsExact() {
            TemporaryMasterGrant grant = validRequest()
                    .capability("approval.document:read")
                    .build();

            assertThat(grant.allows("approval.document:read")).isTrue();
            assertThat(grant.allows("approval.document:approve"))
                    .as("read must not imply write")
                    .isFalse();
            assertThat(grant.allows("hr.employee:read"))
                    .as("one resource must not imply another")
                    .isFalse();
        }

        @Test
        @DisplayName("there is no grant-all: a wildcard capability is refused")
        void noGrantAll() {
            // A "grant all" button is the single feature that would undo this
            // whole design, because under time pressure it is always the one
            // that gets pressed.
            assertThatThrownBy(() -> validRequest().capability("hr.employee:*").build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be ticked individually");

            assertThatThrownBy(() -> validRequest().capability("hr.*:read").build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("each capability states in end-user terms what it exposes")
        void capabilitiesExplainThemselves() {
            // "read every employee's salary history", not "hr.compensation:read".
            // The person ticking the box is a client master, not an engineer.
            TemporaryMasterGrant grant = validRequest()
                    .capability("hr.employee:read")
                    .build();
            assertThat(grant.describeCapabilities("ko"))
                    .anyMatch(text -> text.contains("직원") && !text.contains("hr.employee:read"));
            assertThat(grant.describeCapabilities("en"))
                    .anyMatch(text -> text.toLowerCase().contains("employee"));
        }
    }

    @Nested
    @DisplayName("what a temporary master can never do")
    class ForbiddenAlways {

        @Test
        @DisplayName("the forbidden capabilities are refused even if explicitly ticked")
        void forbiddenCannotBeTicked() {
            // Not "not offered in the UI" — refused at construction, so an API
            // caller who knows the capability names gets the same answer.
            String[] forbidden = {
                    "admin.permission:grant",
                    "admin.master:create",
                    "admin.master:update",
                    "admin.temporaryMaster:issue",
                    "hr.employmentRules:amend",
                    "company.representation:update",
                    "admin.audit:disable",
            };
            for (String capability : forbidden) {
                assertThatThrownBy(() -> validRequest().capability(capability).build())
                        .as("%s must never be grantable to a support session", capability)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("never be granted to a temporary master");
            }
        }

        @Test
        @DisplayName("a bulk employee export requires that specific capability, ticked")
        void bulkExportNeedsItsOwnTick() {
            TemporaryMasterGrant readOnly = validRequest()
                    .capability("hr.employee:read")
                    .build();
            assertThat(readOnly.allows("hr.employee:export"))
                    .as("reading one record must not imply exporting the whole dataset")
                    .isFalse();

            TemporaryMasterGrant exporter = validRequest()
                    .capability("hr.employee:export")
                    .build();
            assertThat(exporter.allows("hr.employee:export")).isTrue();
        }
    }

    @Nested
    @DisplayName("time to live")
    class Ttl {

        @Test
        @DisplayName("the default is four hours")
        void defaultTtl() {
            assertThat(TemporaryMasterGrant.DEFAULT_TTL).isEqualTo(Duration.ofHours(4));
        }

        @Test
        @DisplayName("a TTL beyond twenty-four hours is refused")
        void hardMaximum() {
            assertThatThrownBy(() -> validRequest().timeToLive(Duration.ofHours(25)).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("24");
        }

        @Test
        @DisplayName("a TTL is mandatory; there is no open-ended grant")
        void ttlIsMandatory() {
            assertThatThrownBy(() -> validRequest().timeToLive(null).build())
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> validRequest().timeToLive(Duration.ZERO).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("there is no extension: issue a new grant instead")
        void noExtension() {
            // An extendable grant is an indefinite grant with extra steps.
            for (java.lang.reflect.Method method : TemporaryMasterGrant.class.getMethods()) {
                assertThat(method.getName())
                        .as("no method may extend a grant's life")
                        .doesNotContain("extend")
                        .doesNotContain("renew");
            }
        }

        @Test
        @DisplayName("expiry is enforced on every check, not by a sweep job")
        void expiryIsCheckedNotSwept() {
            TemporaryMasterGrant grant = validRequest()
                    .capability("hr.employee:read")
                    .timeToLive(Duration.ofHours(4))
                    .build();

            assertThat(grant.isActiveAt(NOW.plusHours(3))).isTrue();
            assertThat(grant.allowsAt("hr.employee:read", NOW.plusHours(3))).isTrue();

            // A background sweep that is late, stuck or dead must not extend
            // access by a single second.
            assertThat(grant.isActiveAt(NOW.plusHours(4).plusSeconds(1))).isFalse();
            assertThat(grant.allowsAt("hr.employee:read", NOW.plusHours(4).plusSeconds(1)))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("issuance controls")
    class Issuance {

        @Test
        @DisplayName("a reason is mandatory and cannot be whitespace")
        void reasonMandatory() {
            assertThatThrownBy(() -> validRequest().reason("   ").build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("reason");
            // The ideographic space is easy to type from a Korean IME.
            assertThatThrownBy(() -> validRequest().reason("　").build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("the company name must be typed exactly to confirm")
        void typedConfirmationMustMatch() {
            assertThatThrownBy(() -> validRequest()
                    .typedCompanyNameConfirmation("에이스").build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("company name");

            assertThatThrownBy(() -> validRequest()
                    .typedCompanyNameConfirmation("").build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("issuance requires representative approval under the company's mode")
        void requiresRepresentativeApproval() {
            assertThatThrownBy(() -> validRequest()
                    .approval("doc-1", ApprovalState.IN_PROGRESS,
                            RepresentationMode.several(1), Arrays.asList("rep-1"))
                    .build())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("approval");
        }

        @Test
        @DisplayName("공동대표: a partially approved issuance is refused")
        void jointQuorumApplies() {
            assertThatThrownBy(() -> validRequest()
                    .approval("doc-1", ApprovalState.APPROVED,
                            RepresentationMode.joint(2, 3), Arrays.asList("rep-1"))
                    .build())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("2");
        }

        @Test
        @DisplayName("the installation-wide kill switch refuses issuance permanently")
        void killSwitch() {
            assertThatThrownBy(() -> validRequest().issuanceDisabledForInstallation(true).build())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("permanently disabled");
        }
    }

    @Nested
    @DisplayName("visibility while a session is live")
    class Visibility {

        @Test
        @DisplayName("a banner is produced for every user, naming who and what and until when")
        void bannerIsInformative() {
            TemporaryMasterGrant grant = validRequest()
                    .capability("approval.document:read")
                    .capability("hr.employee:read")
                    .build();

            TemporaryMasterGrant.Banner banner = grant.bannerAt(NOW.plusMinutes(30), "ko");

            assertThat(banner.dismissible())
                    .as("a dismissible banner is a banner nobody sees")
                    .isFalse();
            assertThat(banner.text()).contains("지원 엔지니어");
            assertThat(banner.text()).contains("직원");
            assertThat(banner.revokeAction()).isNotNull();
            assertThat(banner.remaining()).isEqualTo(Duration.ofHours(3).plusMinutes(30));
        }

        @Test
        @DisplayName("no banner once the grant has expired or been revoked")
        void noBannerWhenInactive() {
            TemporaryMasterGrant grant = validRequest().build();
            assertThat(grant.bannerAt(NOW.plusHours(5), "ko")).isNull();
        }

        @Test
        @DisplayName("the engineer's own session is visibly marked")
        void chromeShift() {
            // So the engineer never forgets whose data they are in.
            assertThat(validRequest().build().sessionChrome().accentColour()).isNotNull();
            assertThat(validRequest().build().sessionChrome().headerText())
                    .contains(COMPANY);
        }
    }

    @Nested
    @DisplayName("auditing")
    class Auditing {

        @Test
        @DisplayName("reads are audited, not only writes")
        void readsAreAudited() {
            // The doubled auditing is what makes this feature survivable: a
            // support engineer who only ever reads must still leave a record of
            // exactly what they looked at.
            assertThat(validRequest().build().auditsReads()).isTrue();
        }

        @Test
        @DisplayName("the session report lists every action taken")
        void sessionReport() {
            TemporaryMasterGrant grant = validRequest()
                    .capability("approval.document:read")
                    .build();

            List<TemporaryMasterGrant.AuditedAction> actions = Arrays.asList(
                    new TemporaryMasterGrant.AuditedAction(NOW.plusMinutes(5),
                            "approval.document:read", "doc-991", 1),
                    new TemporaryMasterGrant.AuditedAction(NOW.plusMinutes(9),
                            "approval.document:read", "doc-992", 1));

            TemporaryMasterGrant.SessionReport report =
                    grant.reportFor(actions, NOW.plusHours(4), "expired");

            assertThat(report.totalActions()).isEqualTo(2);
            assertThat(report.rowsTouched()).isEqualTo(2);
            assertThat(report.endedReason()).isEqualTo("expired");
            assertThat(report.reason()).isEqualTo("결재선 설정 오류 조사 (INC-4821)");
            assertThat(report.describe()).contains("approval.document:read");
        }

        @Test
        @DisplayName("a session that did nothing still produces a report")
        void emptySessionStillReports() {
            // "Nothing was accessed" is itself the answer a client wants, and an
            // absent report is indistinguishable from a lost one.
            TemporaryMasterGrant.SessionReport report = validRequest().build()
                    .reportFor(java.util.Collections.<TemporaryMasterGrant.AuditedAction>emptyList(),
                            NOW.plusHours(4), "expired");
            assertThat(report.totalActions()).isZero();
            assertThat(report.describe()).contains("No actions");
        }
    }
}
