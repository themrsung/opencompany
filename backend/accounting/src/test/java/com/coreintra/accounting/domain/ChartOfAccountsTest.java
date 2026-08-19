package com.coreintra.accounting.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Attribute inheritance up the account tree. */
class ChartOfAccountsTest {

    private final List<Account> accounts = new ArrayList<Account>();
    private final Map<String, ChartOfAccounts.Attributes> attributes =
            new LinkedHashMap<String, ChartOfAccounts.Attributes>();

    private Account account(String id, String parentId, String name) {
        Account account = new Account(id, parentId, name, null, null, false);
        if (parentId != null) {
            for (Account existing : accounts) {
                if (existing.id().equals(parentId)) {
                    existing.markAsParent();
                }
            }
        }
        accounts.add(account);
        return account;
    }

    private void describe(String id, ChartOfAccounts.Classification classification,
            ChartOfAccounts.Category category) {
        attributes.put(id, new ChartOfAccounts.Attributes(classification, category));
    }

    private ChartOfAccounts chart() {
        return ChartOfAccounts.of(accounts, attributes);
    }

    @Test
    @DisplayName("an account with no attributes of its own takes its nearest ancestor's")
    void inheritsFromTheNearestAncestor() {
        account("1000", null, "자산");
        account("1100", "1000", "유동자산");
        account("1110", "1100", "보통예금");
        describe("1000", ChartOfAccounts.Classification.OPERATING, ChartOfAccounts.Category.OTHER);
        describe("1100", null, ChartOfAccounts.Category.CASH);

        ChartOfAccounts chart = chart();
        assertThat(chart.classificationOf("1110"))
                .as("set two levels up and never repeated below")
                .isEqualTo(ChartOfAccounts.Classification.OPERATING);
        assertThat(chart.categoryOf("1110"))
                .as("the nearer ancestor wins over the further one")
                .isEqualTo(ChartOfAccounts.Category.CASH);
    }

    @Test
    @DisplayName("an account's own attribute overrides what it would have inherited")
    void ownAttributeWins() {
        account("1000", null, "자산");
        account("1200", "1000", "투자자산");
        describe("1000", ChartOfAccounts.Classification.OPERATING, null);
        describe("1200", ChartOfAccounts.Classification.INVESTING, null);

        assertThat(chart().classificationOf("1200"))
                .isEqualTo(ChartOfAccounts.Classification.INVESTING);
    }

    @Test
    @DisplayName("nothing set anywhere up the chain resolves to nothing, not to a default")
    void unsetStaysUnset() {
        account("5000", null, "비용");
        account("5100", "5000", "급여");

        // A silent default would be a classification nobody chose appearing in a cash-flow
        // statement, which is worse than an obviously missing one.
        assertThat(chart().classificationOf("5100")).isNull();
        assertThat(chart().categoryOf("5100")).isNull();
    }

    @Test
    @DisplayName("only leaves are postable, and a parent stops being one the moment it has a child")
    void onlyLeavesArePostable() {
        account("1000", null, "자산");
        account("1100", "1000", "현금");
        account("1200", "1000", "매출채권");

        List<Account> postable = chart().postableAccounts();
        assertThat(postable).extracting(Account::id).containsExactly("1100", "1200");
        assertThat(chart().account("1000").isPostable()).isFalse();
    }

    @Test
    @DisplayName("the ancestry reads from the account upwards")
    void ancestry() {
        account("1000", null, "자산");
        account("1100", "1000", "유동자산");
        account("1110", "1100", "보통예금");

        assertThat(chart().ancestryOf("1110")).extracting(Account::id)
                .containsExactly("1110", "1100", "1000");
    }

    @Test
    @DisplayName("a cycle written into the tree by hand ends the walk instead of hanging a report")
    void cyclesTerminate() {
        // The database cannot express "no cycles in a tree", so this is reachable by a hand-written
        // UPDATE. A report that hangs is harder to diagnose than a chain that stops early.
        accounts.add(new Account("1100", "1200", "가", null, null, false));
        accounts.add(new Account("1200", "1100", "나", null, null, false));

        assertThat(chart().ancestryOf("1100")).extracting(Account::id)
                .containsExactly("1100", "1200");
        assertThat(chart().classificationOf("1100")).isNull();
    }
}
