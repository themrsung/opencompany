package com.coreintra.accounting.support;

import com.coreintra.accounting.service.AccountingConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * The smallest context that can exercise this module against a real database.
 *
 * <h2>Only this module's entities</h2>
 *
 * <p>The application scans all of {@code com.coreintra}; this scans the accounting entities plus
 * the shared {@link com.coreintra.core.persistence.BusinessInstantEmbeddable} they map through.
 * Scanning everything would validate other modules' entities against a schema stopped at version
 * 8, so a column another team adds in a later migration would turn into a red build here - a test
 * that fails for something it does not test is a test people learn to ignore. Whether the whole
 * set agrees is the application module's question, and it asks it on every integration test there.
 *
 * <p>{@link AccountingConfiguration} is imported explicitly rather than scanned, which also proves
 * the services wire without component scanning finding them - the property the off-switch depends
 * on.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan(basePackages = {"com.coreintra.accounting", "com.coreintra.core.persistence"})
@EnableJpaRepositories(basePackages = "com.coreintra.accounting")
@Import(AccountingConfiguration.class)
public class AccountingTestApplication {

    /**
     * The evaluator the accounting services check against, here over an in-memory grant table.
     *
     * <p>The production one is assembled in the application module from the JPA org and grant
     * directories, which would drag half the schema into this context. This is the real
     * {@code DefaultPermissionEvaluator} with test data behind it, exposed as a bean so a test can
     * grant, withhold and then assert on what was asked.
     */
    @Bean
    public AccountingTestPermissions accountingTestPermissions() {
        return new AccountingTestPermissions();
    }
}
