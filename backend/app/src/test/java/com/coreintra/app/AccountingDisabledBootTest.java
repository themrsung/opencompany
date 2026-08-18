package com.coreintra.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.coreintra.app.support.DatabaseTestSupport;
import com.coreintra.auth.service.AuthenticationService;
import com.coreintra.auth.service.MasterAccountService;
import com.coreintra.auth.service.SessionService;
import com.coreintra.core.org.repository.UserAccountRepository;
import com.coreintra.core.permission.PermissionEvaluator;
import com.coreintra.core.permission.PermissionExplainerService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * ACCEPTANCE: booting with the accounting module disabled leaves the rest of the
 * system fully functional.
 *
 * <p>The accounting module is optional and off-switchable. "Off" has to mean the
 * whole application still starts and every other feature still works — not that
 * accounting throws a friendlier error. A client who never bought accounting
 * should not be able to tell it exists.
 *
 * <p>This test boots the real application context with the flag off and asserts
 * that the core services are present and wired. Without it, the flag would be
 * exercised only by whoever happens to try it first, in production.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "coreintra.accounting.enabled=false")
class AccountingDisabledBootTest {

    @BeforeAll
    static void requireDatabase() {
        DatabaseTestSupport.requireDatabase();
    }

    @Autowired private ApplicationContext context;
    @Autowired private PermissionEvaluator permissionEvaluator;
    @Autowired private AuthenticationService authentication;
    @Autowired private SessionService sessions;
    @Autowired private MasterAccountService masters;
    @Autowired private PermissionExplainerService explainer;
    @Autowired private UserAccountRepository accounts;

    @Test
    @DisplayName("the application context starts with accounting disabled")
    void contextStarts() {
        assertThat(context).isNotNull();
        assertThat(context.getEnvironment().getProperty("coreintra.accounting.enabled"))
                .isEqualTo("false");
    }

    @Test
    @DisplayName("every non-accounting service is still present and usable")
    void coreServicesUnaffected() {
        // The point of the test: nothing here is about accounting, and all of it
        // must work exactly as it does with the module on.
        assertThat(permissionEvaluator).isNotNull();
        assertThat(authentication).isNotNull();
        assertThat(sessions).isNotNull();
        assertThat(masters).isNotNull();
        assertThat(explainer).isNotNull();

        // Not merely present — actually working.
        assertThat(masters.activeMasterCount()).isGreaterThanOrEqualTo(0L);
        assertThat(accounts.findByUsername("nobody-at-all")).isEmpty();
        assertThat(authentication.isEmailOtpAvailable()).isFalse();
    }

    @Test
    @DisplayName("no accounting bean is registered when the module is off")
    void accountingBeansAbsent() {
        String[] beans = context.getBeanDefinitionNames();
        for (String bean : beans) {
            Class<?> type = context.getType(bean);
            if (type == null) {
                continue;
            }
            assertThat(type.getName())
                    .as("bean %s should not be registered while accounting is disabled", bean)
                    .doesNotStartWith("com.coreintra.accounting.service")
                    .doesNotStartWith("com.coreintra.accounting.web");
        }
    }
}
