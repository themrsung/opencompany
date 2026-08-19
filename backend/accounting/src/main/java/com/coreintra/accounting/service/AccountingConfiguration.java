package com.coreintra.accounting.service;

import com.coreintra.accounting.persistence.AccountRepository;
import com.coreintra.accounting.persistence.AccountingBatchRepository;
import com.coreintra.accounting.persistence.AccountingClientRepository;
import com.coreintra.accounting.persistence.AccountingCurrencyRepository;
import com.coreintra.accounting.persistence.BookRepository;
import com.coreintra.accounting.persistence.JournalEntryRepository;
import com.coreintra.accounting.persistence.JournalEntryRevisionRepository;
import com.coreintra.accounting.persistence.JournalPostingRepository;
import com.coreintra.core.permission.PermissionEvaluator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the accounting services, and only when the module is switched on.
 *
 * <h2>Off means the beans are absent, not that they refuse</h2>
 *
 * <p>{@code coreintra.accounting.enabled=false} removes every service here from the context, so a
 * client who never bought accounting cannot reach it by any path and the rest of the application
 * is untouched. A service that existed and threw would still appear in the API surface, in the
 * bean list and in a stack trace, which is a different thing from not being there.
 *
 * <p>The services are plain classes wired by hand rather than annotated with {@code @Service}.
 * That is what makes the flag total: component scanning would find an annotated class regardless
 * of who wired it, and the condition would have to be repeated on each one.
 *
 * <h2>What the flag does not remove</h2>
 *
 * <p>The tables stay - they are Flyway's, and a schema that came and went with a flag could not be
 * migrated forward. The Spring Data repository interfaces also stay: they are registered by the
 * application's {@code @EnableJpaRepositories} scan, which is outside this module. They are inert
 * without the services above, and nothing routes to them.
 */
@Configuration
@ConditionalOnProperty(prefix = "coreintra.accounting", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class AccountingConfiguration {

    /**
     * The one place these services get an evaluator.
     *
     * <p>Injected from the application context rather than constructed here: there is exactly one
     * {@link PermissionEvaluator} in the installation, an ArchUnit rule fails the build on a
     * second implementation, and a module that built its own would be a second permission system
     * that agreed with the first until the day it did not.
     */
    @Bean
    public AccountingGate accountingGate(BookRepository books, PermissionEvaluator evaluator) {
        return new AccountingGate(books, evaluator);
    }

    @Bean
    public BookService accountingBookService(BookRepository books,
            AccountingCurrencyRepository currencies, AccountingClientRepository clients,
            AccountingGate gate) {
        return new BookService(books, currencies, clients, gate);
    }

    @Bean
    public ChartOfAccountsService chartOfAccountsService(AccountRepository accounts,
            JournalPostingRepository postings, AccountingGate gate) {
        return new ChartOfAccountsService(accounts, postings, gate);
    }

    @Bean
    public JournalService journalService(JournalEntryRepository entries,
            JournalPostingRepository postings, JournalEntryRevisionRepository revisions,
            ChartOfAccountsService chart, AccountingGate gate) {
        return new JournalService(entries, postings, revisions, chart, gate);
    }

    @Bean
    public BatchService accountingBatchService(AccountingBatchRepository batches,
            JournalService journal, AccountingGate gate) {
        return new BatchService(batches, journal, gate);
    }

    @Bean
    public AmortizationService amortizationService(BatchService batches, AccountingGate gate) {
        return new AmortizationService(batches, gate);
    }

    @Bean
    public LedgerReportService ledgerReportService(JournalService journal, BatchService batches,
            ChartOfAccountsService chart, AccountingGate gate) {
        return new LedgerReportService(journal, batches, chart, gate);
    }
}
