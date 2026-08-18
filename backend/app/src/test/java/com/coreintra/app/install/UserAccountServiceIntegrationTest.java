package com.coreintra.app.install;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coreintra.app.support.DatabaseTestSupport;
import com.coreintra.auth.service.MasterAccountService;
import com.coreintra.auth.service.UserAccountService;
import com.coreintra.core.org.Employee;
import com.coreintra.core.org.UserAccount;
import com.coreintra.core.org.repository.UserAccountRepository;
import com.coreintra.core.permission.PermissionDeniedException;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.service.EmployeeService;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Creating, linking, suspending and restoring accounts.
 *
 * <p>Lives beside the installer's tests because it needs the same fixture: an
 * empty box, opened once, so that the first account exists and the ordinary
 * permission-checked path can be exercised as the master who was handed it.
 * Running the real installer rather than inserting rows is the point — the
 * account service and the installer make one claim between them, which is that
 * an installation can be opened and then used.
 */
@SpringBootTest
@ActiveProfiles("test")
class UserAccountServiceIntegrationTest {

    private static final LocalDate TODAY = LocalDate.now();

    @BeforeAll
    static void requireDatabase() {
        DatabaseTestSupport.requireDatabase();
    }

    @Autowired private DataSource dataSource;
    @Autowired private InstallationService installation;
    @Autowired private UserAccountService accountService;
    @Autowired private UserAccountRepository accounts;
    @Autowired private MasterAccountService masters;
    @Autowired private EmployeeService employees;

    private PermissionPrincipal master;
    private String companyId;

    @BeforeEach
    void openTheBox() {
        EmptyInstallation.reset(dataSource);
        InstallationResult installed = installation.install(InstallationPlan.builder()
                .company("HANBIT", "한빛산업 주식회사", "Hanbit Industries")
                .master("daepyo", "김서연")
                .build());
        companyId = installed.companyId();
        master = PermissionPrincipal.master(installed.masterAccountId(), "김서연", null);
    }

    private Employee hire(String employeeNumber, String name) {
        return employees.create(master, companyId, employeeNumber, name, null,
                employeeNumber + "@hanbit.example", TODAY.minusYears(1), TODAY);
    }

    @Test
    @DisplayName("an administrator creates an account for an employee, with no password anywhere")
    void createsAnAccountForAnEmployee() {
        Employee minjun = hire("20190041", "김민준");

        UserAccount account = accountService.create(master, "minjun", "김민준",
                UserAccount.AccountKind.USER, minjun.id(), TODAY);

        assertThat(account.employeeId()).isEqualTo(minjun.id());
        assertThat(account.isActive()).isTrue();
        assertThat(account.isMaster()).isFalse();
        assertThat(columnsOf("user_account"))
                .as("ADR 0006: there is no password column, so there is nothing here to leak")
                .doesNotContainKeys("password", "password_hash", "password_digest");
    }

    @Test
    @DisplayName("a caller without admin.account:create cannot mint one")
    void refusesWithoutThePermission() {
        final Employee minjun = hire("20190041", "김민준");
        final PermissionPrincipal nobody =
                PermissionPrincipal.user("acc-nobody", "이준호", minjun.id());

        assertThatThrownBy(new ThrowingCallable() {
            @Override
            public void call() {
                accountService.create(nobody, "sneaky", "이준호", UserAccount.AccountKind.USER,
                        minjun.id(), TODAY);
            }
        }).isInstanceOf(PermissionDeniedException.class);

        assertThat(accounts.findByUsername("sneaky")).isEmpty();
    }

    @Test
    @DisplayName("a username in use is refused by name rather than by constraint")
    void refusesADuplicateUsername() {
        assertThatThrownBy(new ThrowingCallable() {
            @Override
            public void call() {
                accountService.create(master, "daepyo", "다른 사람",
                        UserAccount.AccountKind.USER, null, TODAY);
            }
        })
                .isInstanceOf(UserAccountService.AccountConflictException.class)
                .hasMessageContaining("daepyo");
    }

    @Test
    @DisplayName("one person, one account")
    void refusesASecondAccountForTheSameEmployee() {
        final Employee minjun = hire("20190041", "김민준");
        accountService.create(master, "minjun", "김민준", UserAccount.AccountKind.USER, minjun.id(),
                TODAY);

        assertThatThrownBy(new ThrowingCallable() {
            @Override
            public void call() {
                accountService.create(master, "minjun2", "김민준", UserAccount.AccountKind.USER,
                        minjun.id(), TODAY);
            }
        })
                .isInstanceOf(UserAccountService.AccountConflictException.class)
                .hasMessageContaining("minjun");
    }

    @Test
    @DisplayName("a service account is never a person")
    void refusesToLinkAServiceAccountToAnEmployee() {
        final Employee minjun = hire("20190041", "김민준");

        assertThatThrownBy(new ThrowingCallable() {
            @Override
            public void call() {
                accountService.create(master, "reporting-bot", "리포팅 연동",
                        UserAccount.AccountKind.SERVICE_ACCOUNT, minjun.id(), TODAY);
            }
        }).isInstanceOf(IllegalArgumentException.class);

        UserAccount service = accountService.create(master, "reporting-bot", "리포팅 연동",
                UserAccount.AccountKind.SERVICE_ACCOUNT, null, TODAY);
        assertThat(service.employeeId()).isNull();
    }

    @Test
    @DisplayName("an existing account can be linked to the person it belongs to")
    void linksAnAccountToAnEmployee() {
        Employee seoyeon = hire("20180001", "김서연");
        UserAccount unlinked = accountService.create(master, "seoyeon", "김서연",
                UserAccount.AccountKind.USER, null, TODAY);

        UserAccount linked =
                accountService.linkToEmployee(master, unlinked.id(), seoyeon.id(), TODAY);

        assertThat(linked.employeeId()).isEqualTo(seoyeon.id());
    }

    @Test
    @DisplayName("deactivating suspends without deleting, and reactivating puts it back")
    void deactivatesAndReactivates() {
        Employee minjun = hire("20190041", "김민준");
        UserAccount account = accountService.create(master, "minjun", "김민준",
                UserAccount.AccountKind.USER, minjun.id(), TODAY);

        assertThat(accountService.deactivate(master, account.id(), TODAY).isActive()).isFalse();
        assertThat(accounts.findById(account.id()))
                .as("no hard delete: the approvals this person signed still name them")
                .isPresent();
        assertThat(accountService.reactivate(master, account.id(), TODAY).isActive()).isTrue();
    }

    @Test
    @DisplayName("the last active master cannot be deactivated out of existence")
    void refusesToDeactivateTheLastMaster() {
        final String masterAccountId = master.accountId();

        assertThatThrownBy(new ThrowingCallable() {
            @Override
            public void call() {
                accountService.deactivate(master, masterAccountId, TODAY);
            }
        }).isInstanceOf(MasterAccountService.LastMasterException.class);

        assertThat(accounts.countByMasterTrueAndActiveTrue()).isEqualTo(1L);
    }

    @Test
    @DisplayName("the first-account door is shut once there is an account")
    void refusesToBootstrapASecondFirstAccount() {
        assertThatThrownBy(new ThrowingCallable() {
            @Override
            public void call() {
                accountService.createFirstAccount("second-master", "누군가");
            }
        }).isInstanceOf(UserAccountService.InstallationNotEmptyException.class);

        assertThat(accounts.findByUsername("second-master")).isEmpty();
    }

    private Map<String, Boolean> columnsOf(String table) {
        Map<String, Boolean> columns = new LinkedHashMap<String, Boolean>();
        for (String column : new org.springframework.jdbc.core.JdbcTemplate(dataSource)
                .queryForList("select column_name from information_schema.columns "
                        + "where table_name = ?", String.class, table)) {
            columns.put(column, Boolean.TRUE);
        }
        return columns;
    }
}
