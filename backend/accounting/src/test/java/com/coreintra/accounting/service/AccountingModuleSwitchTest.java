package com.coreintra.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.coreintra.accounting.persistence.AccountRepository;
import com.coreintra.accounting.persistence.AccountingBatchRepository;
import com.coreintra.accounting.persistence.AccountingClientRepository;
import com.coreintra.accounting.persistence.AccountingCurrencyRepository;
import com.coreintra.accounting.persistence.BookRepository;
import com.coreintra.accounting.persistence.JournalEntryRepository;
import com.coreintra.accounting.persistence.JournalEntryRevisionRepository;
import com.coreintra.accounting.persistence.JournalPostingRepository;
import com.coreintra.accounting.support.AccountingTestPermissions;
import com.coreintra.core.permission.PermissionEvaluator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * ACCEPTANCE (this module's half): switching accounting off removes its services from the context.
 *
 * <p>The application module has the other half - that the rest of the system boots and works with
 * the flag off. This is the half that has to be true here: "off" means the beans are absent, not
 * that they exist and refuse. A service that existed and threw would still show up in the bean
 * list, in the API surface and in a stack trace, and a client who never bought accounting would be
 * able to tell it was there.
 *
 * <p>No database: the question is only which beans the condition produces.
 */
class AccountingModuleSwitchTest {

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withUserConfiguration(AccountingConfiguration.class)
            .withBean(BookRepository.class, () -> mock(BookRepository.class))
            .withBean(AccountRepository.class, () -> mock(AccountRepository.class))
            .withBean(AccountingCurrencyRepository.class,
                    () -> mock(AccountingCurrencyRepository.class))
            .withBean(AccountingClientRepository.class, () -> mock(AccountingClientRepository.class))
            .withBean(AccountingBatchRepository.class, () -> mock(AccountingBatchRepository.class))
            .withBean(JournalEntryRepository.class, () -> mock(JournalEntryRepository.class))
            .withBean(JournalPostingRepository.class, () -> mock(JournalPostingRepository.class))
            .withBean(JournalEntryRevisionRepository.class,
                    () -> mock(JournalEntryRevisionRepository.class))
            // Supplied by the application, never by this module: there is one evaluator in the
            // installation and accounting is a consumer of it.
            .withBean(PermissionEvaluator.class, () -> new AccountingTestPermissions());

    @Test
    @DisplayName("with the module off, not one accounting service is registered")
    void offMeansAbsent() {
        contexts.withPropertyValues("coreintra.accounting.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(BookService.class);
            assertThat(context).doesNotHaveBean(ChartOfAccountsService.class);
            assertThat(context).doesNotHaveBean(JournalService.class);
            assertThat(context).doesNotHaveBean(BatchService.class);
            assertThat(context).doesNotHaveBean(AmortizationService.class);
            assertThat(context).doesNotHaveBean(LedgerReportService.class);
            assertThat(context).doesNotHaveBean(AccountingConfiguration.class);
            // Including the gate. It holds the evaluator rather than being one, so leaving it
            // behind would be harmless - and it would also be the first thing to grow a caller.
            assertThat(context).doesNotHaveBean(AccountingGate.class);
        });
    }

    @Test
    @DisplayName("with the module on, the whole service layer is wired")
    void onMeansWired() {
        contexts.withPropertyValues("coreintra.accounting.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(BookService.class);
            assertThat(context).hasSingleBean(ChartOfAccountsService.class);
            assertThat(context).hasSingleBean(JournalService.class);
            assertThat(context).hasSingleBean(BatchService.class);
            assertThat(context).hasSingleBean(AmortizationService.class);
            assertThat(context).hasSingleBean(LedgerReportService.class);
            assertThat(context).hasSingleBean(AccountingGate.class);
        });
    }

    @Test
    @DisplayName("an installation that has never heard of the flag gets accounting")
    void missingPropertyMeansOn() {
        // The module ships enabled; turning it off is a decision someone makes and records.
        contexts.run(context -> assertThat(context).hasSingleBean(JournalService.class));
    }
}
