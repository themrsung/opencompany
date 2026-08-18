package com.coreintra.app.api.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.coreintra.app.api.permission.CurrentPrincipal;
import com.coreintra.compat.Immutables;
import com.coreintra.core.permission.DefaultPermissionEvaluator;
import com.coreintra.core.permission.GrantDirectory;
import com.coreintra.core.permission.GrantSource;
import com.coreintra.core.permission.OrgDirectory;
import com.coreintra.core.permission.PermissionEvaluator;
import com.coreintra.core.permission.PermissionGrant;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionScope;
import com.coreintra.core.permission.PrincipalOrgState;
import com.coreintra.runtime.support.TemporaryMasterGrantRow;
import com.coreintra.runtime.support.TemporaryMasterIssuance;
import com.coreintra.runtime.support.TemporaryMasterService;
import com.coreintra.runtime.support.TemporaryMasterSessionReporter;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The checks that live at the edge of the highest-risk feature in the system.
 *
 * <p>The invariants themselves — the 24-hour ceiling, the never-grantable list,
 * the quorum — belong to the service and the schema and are tested there. What
 * is tested here is what only the controller can get wrong: who may ask, whether
 * the confirmation was really typed, and whether anything can be granted that
 * the approving 대표 was never shown in words.
 *
 * <p>There is also a test for something that is not here, which is the point of
 * writing it down: no endpoint extends a session.
 */
class TemporaryMasterControllerTest {

    private TemporaryMasterService sessions;
    private TemporaryMasterSessionReporter reporter;
    private PermissionEvaluator evaluator;
    private TemporaryMasterController controller;

    private static final PermissionPrincipal MASTER =
            PermissionPrincipal.master("acc-master", "김대표", "emp-1");
    private static final PermissionPrincipal ORDINARY =
            PermissionPrincipal.user("acc-user", "이사원", "emp-2");

    @BeforeEach
    void setUp() {
        sessions = mock(TemporaryMasterService.class);
        reporter = mock(TemporaryMasterSessionReporter.class);
        evaluator = permissiveEvaluator();
        when(sessions.issuanceDisabled()).thenReturn(Boolean.FALSE);
        controller = new TemporaryMasterController(sessions, reporter, evaluator, principalOf(MASTER));
    }

    /**
     * The real evaluator, over a directory that grants what this controller asks
     * for.
     *
     * <p>Not a mock. {@code PermissionDecision} is final, deliberately, and a
     * second {@code PermissionEvaluator} implementation is forbidden by an
     * ArchUnit rule — both of which exist so that nothing can quietly stand in
     * for the one gate. Honouring that here costs six lines and means these
     * tests exercise the same decision path production does; the gate itself is
     * tested in core-domain.
     */
    private static PermissionEvaluator permissiveEvaluator() {
        OrgDirectory org = new OrgDirectory() {
            @Override
            public PrincipalOrgState resolve(PermissionPrincipal who, LocalDate asOf) {
                return new PrincipalOrgState(who.employeeId(), Immutables.setOf("hq"),
                        Immutables.setOf("acme"), Immutables.setOf("이사"), Immutables.setOf("지원"));
            }

            @Override
            public boolean isInSubtree(String ancestorId, String candidateDescendantId,
                    LocalDate asOf) {
                return true;
            }
        };
        final List<PermissionGrant> grants = Immutables.listOf(
                PermissionGrant.allow(PermissionKey.parse("admin.temporaryMaster:issue"),
                        PermissionScope.ALL, GrantSource.USER_ACCOUNT, "acc-master", "마스터"),
                PermissionGrant.allow(PermissionKey.parse("support.temporary_master:revoke"),
                        PermissionScope.ALL, GrantSource.USER_ACCOUNT, "acc-master", "마스터"),
                PermissionGrant.allow(PermissionKey.parse("support.temporary_master:read"),
                        PermissionScope.ALL, GrantSource.USER_ACCOUNT, "acc-master", "마스터"));
        GrantDirectory directory = new GrantDirectory() {
            @Override
            public List<PermissionGrant> grantsFor(PermissionPrincipal who,
                    PrincipalOrgState orgState) {
                return grants;
            }
        };
        return new DefaultPermissionEvaluator(org, directory);
    }

    private static CurrentPrincipal principalOf(final PermissionPrincipal principal) {
        return new CurrentPrincipal() {
            @Override
            public PermissionPrincipal require() {
                return principal;
            }
        };
    }

    private static TemporaryMasterController.IssueRequest validRequest() {
        TemporaryMasterController.IssueRequest request =
                new TemporaryMasterController.IssueRequest();
        request.setCompanyId("acme");
        request.setCompanyName("주식회사 에이컴");
        request.setAccountId("acc-support");
        request.setEngineerName("박지원");
        request.setReason("결재 상신이 실패하는 현상을 확인합니다");
        request.setTypedCompanyName("주식회사 에이컴");
        request.setCapabilities(Immutables.listOf("approval.document:read"));
        request.setApprovalDocumentId("doc-1");
        request.setRepresentationMode("SEVERAL");
        request.setRequiredApprovals(1);

        TemporaryMasterController.Approver approver = new TemporaryMasterController.Approver();
        approver.setAccountId("acc-rep");
        approver.setName("김대표");
        request.setApprovedBy(Immutables.listOf(approver));
        return request;
    }

    @Nested
    @DisplayName("who may issue")
    class WhoMayIssue {

        @Test
        @DisplayName("an account with the permission but without master is still refused")
        void nonMasterRefused() {
            TemporaryMasterController asUser = new TemporaryMasterController(
                    sessions, reporter, evaluator, principalOf(ORDINARY));

            assertThatThrownBy(() -> asUser.issue(validRequest()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("master");

            verify(sessions, never()).issue(any(TemporaryMasterIssuance.class));
        }

        @Test
        @DisplayName("issuance disabled for the installation refuses, and says it cannot be undone")
        void killSwitchRefuses() {
            when(sessions.issuanceDisabled()).thenReturn(Boolean.TRUE);

            assertThatThrownBy(() -> controller.issue(validRequest()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot be re-enabled");

            verify(sessions, never()).issue(any(TemporaryMasterIssuance.class));
        }
    }

    @Nested
    @DisplayName("the confirmation is a confirmation, not a checkbox")
    class Confirmation {

        @Test
        @DisplayName("a mismatched company name refuses and quotes what should have been typed")
        void mismatchRefused() {
            TemporaryMasterController.IssueRequest request = validRequest();
            request.setTypedCompanyName("에이컴");

            assertThatThrownBy(() -> controller.issue(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("주식회사 에이컴");

            verify(sessions, never()).issue(any(TemporaryMasterIssuance.class));
        }

        @Test
        @DisplayName("surrounding whitespace does not defeat it, and does not pass it either")
        void whitespaceTolerated() {
            TemporaryMasterController.IssueRequest request = validRequest();
            request.setTypedCompanyName("  주식회사 에이컴  ");
            // Built before the stubbing call, not inside it: grantStub() stubs
            // its own mock, and Mockito reads a stubbing that starts while
            // another is open as an unfinished one.
            TemporaryMasterGrantRow issued = grantStub();
            when(sessions.issue(any(TemporaryMasterIssuance.class))).thenReturn(issued);

            controller.issue(request);

            verify(sessions).issue(any(TemporaryMasterIssuance.class));
        }
    }

    @Nested
    @DisplayName("nothing is granted that was not described in words")
    class DescribedCapabilities {

        @Test
        @DisplayName("a capability with no plain-language wording refuses issuance")
        void undescribedCapabilityRefused() {
            TemporaryMasterController.IssueRequest request = validRequest();
            request.setCapabilities(Immutables.listOf("approval.document:read", "some.new:thing"));

            assertThatThrownBy(() -> controller.issue(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("plain-language description");

            verify(sessions, never()).issue(any(TemporaryMasterIssuance.class));
        }

        @Test
        @DisplayName("a never-grantable capability is refused before it reaches the service")
        void neverGrantableRefused() {
            TemporaryMasterController.IssueRequest request = validRequest();
            request.setCapabilities(Immutables.listOf("admin.permission:grant"));

            assertThatThrownBy(() -> controller.issue(request))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(sessions, never()).issue(any(TemporaryMasterIssuance.class));
        }

        @Test
        @DisplayName("an empty capability list is allowed — a session that can read nothing is valid")
        void nothingTickedIsValid() {
            TemporaryMasterController.IssueRequest request = validRequest();
            request.setCapabilities(new ArrayList<String>());
            TemporaryMasterGrantRow issued = grantStub();
            when(sessions.issue(any(TemporaryMasterIssuance.class))).thenReturn(issued);

            controller.issue(request);

            verify(sessions).issue(any(TemporaryMasterIssuance.class));
        }
    }

    @Nested
    @DisplayName("what is deliberately absent")
    class Absences {

        @Test
        @DisplayName("no endpoint extends a session")
        void thereIsNoExtension() {
            List<String> suspicious = new ArrayList<String>();
            for (Method method : TemporaryMasterController.class.getDeclaredMethods()) {
                String name = method.getName().toLowerCase(java.util.Locale.ROOT);
                if (name.contains("extend") || name.contains("prolong") || name.contains("renew")) {
                    suspicious.add(method.getName());
                }
            }
            // §8: no extension — issue a new one. An extension endpoint turns the
            // hard 24-hour ceiling into a suggestion, one renewal at a time.
            assertThat(suspicious).isEmpty();
        }

        @Test
        @DisplayName("no endpoint re-enables issuance after the kill switch")
        void thereIsNoUndo() {
            List<String> suspicious = new ArrayList<String>();
            for (Method method : TemporaryMasterController.class.getDeclaredMethods()) {
                String name = method.getName().toLowerCase(java.util.Locale.ROOT);
                if (name.contains("reenable") || name.contains("enableissuance")
                        || name.contains("undodisable")) {
                    suspicious.add(method.getName());
                }
            }
            assertThat(suspicious).isEmpty();
        }

        @Test
        @DisplayName("the capability catalogue offers nothing on the never-grantable list")
        void catalogueIsSafe() {
            List<TemporaryMasterController.Capability> offered = controller.capabilities();
            List<String> keys = new ArrayList<String>();
            for (TemporaryMasterController.Capability capability : offered) {
                keys.add(capability.getKey());
            }
            assertThat(keys).isNotEmpty();
            assertThat(keys).doesNotContainAnyElementsOf(
                    Arrays.asList("admin.permission:grant", "admin.master:create",
                            "admin.temporaryMaster:issue", "admin.audit:delete"));
        }
    }

    private static TemporaryMasterGrantRow grantStub() {
        TemporaryMasterGrantRow grant = mock(TemporaryMasterGrantRow.class);
        when(grant.id()).thenReturn("grant-1");
        when(grant.capabilities()).thenReturn(Immutables.<String>setOf());
        return grant;
    }

    @Test
    @DisplayName("revoking a session that does not exist says so rather than pretending")
    void revokeUnknown() {
        when(sessions.grantById(anyString())).thenReturn(null);

        assertThatThrownBy(() -> controller.revoke("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No such support session");
    }
}
