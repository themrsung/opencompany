package com.coreintra.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coreintra.core.org.UserAccount;
import com.coreintra.core.org.repository.UserAccountRepository;
import com.coreintra.core.permission.EffectivePermissions;
import com.coreintra.core.permission.GrantSource;
import com.coreintra.core.permission.PermissionDecision;
import com.coreintra.core.permission.PermissionDeniedException;
import com.coreintra.core.permission.PermissionExplainerService;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionScope;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The explainer, asked about a person rather than an account.
 *
 * <p>Thin by design, so these tests check the two things the wrapper actually
 * decides - which account a person maps to, and what to say when they have none -
 * and confirm that the inspection gate underneath is still the explainer's,
 * neither re-implemented nor bypassed.
 */
class EffectivePermissionsServiceTest {

    private static final PermissionKey READ_EMPLOYEE = PermissionKey.parse("hr.employee:read");

    private OrgFixture fixture;
    private PermissionPrincipal admin;
    private EffectivePermissionsService service;

    @BeforeEach
    void setUp() {
        fixture = new OrgFixture();
        admin = fixture.administrator();
        fixture.rank("사원", "사원", 10);

        PermissionExplainerService explainer = new PermissionExplainerService(fixture.evaluator,
                accountRepositoryDouble(), fixture.book);
        service = new EffectivePermissionsService(explainer, fixture.book.accounts(), fixture.employees);
    }

    /**
     * {@code PermissionExplainerService} takes the JPA {@code UserAccountRepository},
     * of which it calls exactly one method. Hand-writing the other thirty as
     * {@code UnsupportedOperationException} would be more code than the test, so
     * the double is a proxy that answers the one and refuses the rest loudly.
     */
    private UserAccountRepository accountRepositoryDouble() {
        return (UserAccountRepository) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {UserAccountRepository.class}, new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("findById".equals(method.getName())) {
                            return fixture.book.accounts().findById((String) args[0]);
                        }
                        throw new UnsupportedOperationException(
                                "the explainer is not expected to call " + method.getName());
                    }
                });
    }

    @Test
    @DisplayName("inspecting your own authority never needs a permission")
    void ownAuthorityNeedsNothing() {
        PermissionPrincipal person = fixture.person("emp-1", "김민준");
        fixture.position("emp-1", "finance", "사원", OrgFixture.JANUARY);
        fixture.grant(GrantSource.RANK, "사원", "hr.leaveRequest:create", PermissionScope.SELF);

        EffectivePermissions mine = service.explainEmployee(person, "emp-1", OrgFixture.TODAY);

        assertThat(mine.grants()).hasSize(1);
        assertThat(mine.grants().get(0).sourceLabel()).isEqualTo("사원");
    }

    @Test
    @DisplayName("inspecting somebody else needs admin.permission:read")
    void inspectingOthersIsGated() {
        PermissionPrincipal person = fixture.person("emp-1", "김민준");
        fixture.position("emp-1", "finance", "사원", OrgFixture.JANUARY);
        fixture.person("emp-2", "이서준");

        assertThatThrownBy(() -> service.explainEmployee(person, "emp-2", OrgFixture.TODAY))
                .isInstanceOf(PermissionDeniedException.class);
        assertThat(service.explainEmployee(admin, "emp-1", OrgFixture.TODAY).orgState().orgUnitIds())
                .containsExactly("finance");
    }

    @Test
    @DisplayName("a person with no account is said to have none, not to have nothing")
    void personWithoutAnAccount() {
        fixture.employee("emp-3", "박지훈");

        assertThatThrownBy(() -> service.explainEmployee(admin, "emp-3", OrgFixture.TODAY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no account");
    }

    @Test
    @DisplayName("a denial is explained rather than thrown, because that is the question admins ask")
    void deniedDecisionsAreReturned() {
        PermissionPrincipal person = fixture.person("emp-1", "김민준");
        fixture.position("emp-1", "finance", "사원", OrgFixture.JANUARY);
        fixture.employee("emp-2", "이서준");

        PermissionDecision decision = service.explainDecisionAboutEmployee(admin, person.accountId(),
                READ_EMPLOYEE, "emp-2", OrgFixture.TODAY);

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.summary()).contains("denied by default");
    }

    @Test
    @DisplayName("the grant chain names the source that decided it")
    void grantChainNamesItsSource() {
        PermissionPrincipal person = fixture.person("emp-1", "김민준");
        fixture.position("emp-1", "finance", "사원", OrgFixture.JANUARY);
        fixture.grant(GrantSource.ORG_UNIT, "finance", "hr.employee:read", PermissionScope.ORG_UNIT);
        fixture.employee("emp-2", "이서준");
        fixture.position("emp-2", "finance", "사원", OrgFixture.JANUARY);

        PermissionDecision decision = service.explainDecisionAboutEmployee(admin, person.accountId(),
                READ_EMPLOYEE, "emp-2", OrgFixture.TODAY);

        assertThat(decision.isAllowed()).isTrue();
        assertThat(decision.decidingGrant().sourceLabel()).isEqualTo("재경팀");
        assertThat(decision.explain()).contains("hr.employee:read");
    }

    @Test
    @DisplayName("an account id can still be used directly, for callers that hold one")
    void accountIdStillWorks() {
        PermissionPrincipal person = fixture.person("emp-1", "김민준");
        fixture.position("emp-1", "finance", "사원", OrgFixture.JANUARY);
        fixture.grant(GrantSource.RANK, "사원", "hr.leaveRequest:create", PermissionScope.SELF);

        Optional<UserAccount> account = fixture.book.accounts().findByEmployeeId("emp-1");

        assertThat(account).isPresent();
        assertThat(service.explainAccount(admin, account.get().id(), OrgFixture.TODAY).grants()).hasSize(1);
    }
}
