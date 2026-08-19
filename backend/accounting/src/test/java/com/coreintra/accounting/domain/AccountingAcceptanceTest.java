package com.coreintra.accounting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coreintra.businesstime.BusinessInstant;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The accounting engine's invariants.
 *
 * <p>Two of these are named acceptance tests from the brief and must never
 * regress: debits equal credits exactly or the entry does not exist, and
 * rounding never occurs on write.
 */
class AccountingAcceptanceTest {

    private static final BusinessInstant WHEN =
            BusinessInstant.of(LocalDate.of(2026, 8, 30), 9, 0, 0);

    private static List<Posting> postings(Posting... items) {
        return new ArrayList<Posting>(Arrays.asList(items));
    }

    @Nested
    @DisplayName("debits equal credits, exactly, or the entry does not exist")
    class Balance {

        @Test
        @DisplayName("a balanced entry is created")
        void balancedEntryIsCreated() {
            Entry entry = Entry.post("e1", "book", WHEN, "비품 구입", postings(
                    Posting.debit("5100", Amount.parse("1,000,000")),
                    Posting.credit("1100", Amount.parse("1000000"))), null);

            assertThat(entry.totalDebits()).isEqualTo(Amount.parse("1000000"));
            assertThat(entry.isReportable()).isTrue();
        }

        @Test
        @DisplayName("an unbalanced entry cannot be constructed at all")
        void unbalancedEntryCannotExist() {
            // Not "is flagged", not "fails on save": there is no unbalanced Entry
            // object for any code path to hold, log, or half-write.
            assertThatThrownBy(() -> Entry.post("e1", "book", WHEN, "틀린 전표", postings(
                    Posting.debit("5100", Amount.parse("1000000")),
                    Posting.credit("1100", Amount.parse("999999"))), null))
                    .isInstanceOf(Entry.UnbalancedEntryException.class)
                    .hasMessageContaining("차변과 대변이 일치하지 않습니다")
                    .hasMessageContaining("has not been created");
        }

        @Test
        @DisplayName("the reported difference is exact, so the user can find the mistake")
        void differenceIsExact() {
            try {
                Entry.post("e1", "book", WHEN, "x", postings(
                        Posting.debit("5100", Amount.parse("100.00")),
                        Posting.credit("1100", Amount.parse("99.99"))), null);
                org.junit.jupiter.api.Assertions.fail("expected refusal");
            } catch (Entry.UnbalancedEntryException e) {
                assertThat(e.difference()).isEqualTo(Amount.parse("0.01"));
            }
        }

        @Test
        @DisplayName("differing SCALE is not an imbalance: 1000.00 balances 1000")
        void scaleIsNotImbalance() {
            // BigDecimal.equals would reject this. An entry refused for scale
            // would be a bug that looks like a balance error.
            Entry entry = Entry.post("e1", "book", WHEN, "x", postings(
                    Posting.debit("5100", Amount.parse("1000.00")),
                    Posting.credit("1100", Amount.parse("1000"))), null);
            assertThat(entry).isNotNull();
        }

        @Test
        @DisplayName("a rounding gap IS an imbalance, and is never absorbed")
        void roundingGapIsRefused() {
            // Absorbing 0.0000001 would be a plug, and a plug is how a ledger
            // stops meaning anything.
            assertThatThrownBy(() -> Entry.post("e1", "book", WHEN, "x", postings(
                    Posting.debit("5100", Amount.parse("1000.0000001")),
                    Posting.credit("1100", Amount.parse("1000"))), null))
                    .isInstanceOf(Entry.UnbalancedEntryException.class);
        }

        @Test
        @DisplayName("a single-sided entry is refused")
        void singleSidedRefused() {
            assertThatThrownBy(() -> Entry.post("e1", "book", WHEN, "x", postings(
                    Posting.debit("5100", Amount.parse("100"))), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least two postings");
        }

        @Test
        @DisplayName("a multi-leg entry balances across all legs")
        void multiLegBalances() {
            Entry entry = Entry.post("e1", "book", WHEN, "급여 지급", postings(
                    Posting.debit("5200", Amount.parse("3,000,000")),
                    Posting.credit("2100", Amount.parse("300000")),
                    Posting.credit("1100", Amount.parse("2700000"))), null);
            assertThat(entry.totalDebits()).isEqualTo(Amount.parse("3000000"));
        }

        @Test
        @DisplayName("foreign-currency entries balance in the BASE currency")
        void foreignCurrencyBalancesInBase() {
            // 1,000 USD at 1,320 = 1,320,000 KRW. The entry balances on the base
            // amounts, not the account amounts.
            Entry entry = Entry.post("e1", "book", WHEN, "해외 매입", postings(
                    Posting.foreignCurrency("5100", Amount.parse("1000"), "USD",
                            Amount.parse("1320000"), new BigDecimal("1320")),
                    Posting.credit("1100", Amount.parse("1320000"))), null);
            assertThat(entry.postings().get(0).rate()).isEqualByComparingTo("1320");
        }
    }

    @Nested
    @DisplayName("rounding never occurs on write")
    class NoRoundingOnWrite {

        @Test
        @DisplayName("full precision survives storage regardless of display decimals")
        void fullPrecisionSurvives() {
            Amount stored = Amount.parse("1234.56789012345");
            assertThat(stored.toExactString()).isEqualTo("1234.56789012345");

            // KRW displays 0 decimals. That is presentation, and it changes
            // nothing about what is stored.
            Currency krw = Currency.krw();
            assertThat(krw.formatForDisplay(stored)).isEqualTo("1235");
            assertThat(stored.toExactString())
                    .as("displaying a value must not alter it")
                    .isEqualTo("1234.56789012345");
            assertThat(krw.displayHidesPrecision(stored))
                    .as("the UI must know to offer the exact value")
                    .isTrue();
        }

        @Test
        @DisplayName("arithmetic keeps every digit it produces")
        void arithmeticDoesNotRound() {
            // An allocation of 1,000,000 across three ways produces a repeating
            // decimal. Nothing here truncates it.
            Amount third = Amount.parse("1000000").multiply(
                    new BigDecimal("1").divide(new BigDecimal("3"), 20, java.math.RoundingMode.DOWN));
            assertThat(third.toExactString()).startsWith("333333.33333333333333");
        }

        @Test
        @DisplayName("round() returns a String so a rounded figure cannot flow back in")
        void roundReturnsAString() throws NoSuchMethodException {
            // A rounded Amount could be passed straight into a posting. A String
            // cannot, without someone deliberately re-parsing it.
            assertThat(Amount.class.getMethod("round", int.class).getReturnType())
                    .isEqualTo(String.class);
        }

        @Test
        @DisplayName("no factory accepts a double, so the mistake cannot be written")
        void noDoubleFactoryExists() {
            // Enforced at BUILD time by the ArchUnit rules rather than at
            // runtime: a method that exists to throw is still a method with a
            // double in its signature.
            for (java.lang.reflect.Method method : Amount.class.getMethods()) {
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertThat(parameter)
                            .as("Amount.%s must not accept floating point", method.getName())
                            .isNotEqualTo(double.class)
                            .isNotEqualTo(float.class)
                            .isNotEqualTo(Double.class)
                            .isNotEqualTo(Float.class);
                }
            }
        }

        @Test
        @DisplayName("0.1 + 0.2 is exactly 0.3, which double cannot manage")
        void classicFloatingPointCase() {
            assertThat(Amount.parse("0.1").add(Amount.parse("0.2")))
                    .isEqualTo(Amount.parse("0.3"));
            assertThat(Amount.parse("0.1").add(Amount.parse("0.2")).toExactString())
                    .isEqualTo("0.3");
        }
    }

    @Nested
    @DisplayName("wire format")
    class WireFormat {

        @Test
        @DisplayName("thousands separators are accepted, because people paste from spreadsheets")
        void acceptsSeparators() {
            assertThat(Amount.parse("1,234,567.89").toExactString()).isEqualTo("1234567.89");
            assertThat(Amount.parse("-1,000").toExactString()).isEqualTo("-1000");
        }

        @Test
        @DisplayName("ambiguous forms are refused rather than guessed at")
        void refusesAmbiguousForms() {
            assertThatThrownBy(() -> Amount.parse("1e3"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ambiguous about precision");
            assertThatThrownBy(() -> Amount.parse(".5"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Amount.parse("1."))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Amount.parse("1,00"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("numeric equality and hashCode agree, so sets and sorts behave alike")
        void equalityIsNumeric() {
            assertThat(Amount.parse("1.00")).isEqualTo(Amount.parse("1.0"));
            assertThat(Amount.parse("1.00").hashCode()).isEqualTo(Amount.parse("1.0").hashCode());

            java.util.Set<Amount> set = new java.util.HashSet<Amount>();
            set.add(Amount.parse("1.00"));
            set.add(Amount.parse("1.0"));
            assertThat(set).hasSize(1);
        }
    }

    @Nested
    @DisplayName("chart of accounts")
    class ChartOfAccounts {

        @Test
        @DisplayName("the type comes from the id prefix and cannot be changed")
        void typeIsImmutableAndDerived() {
            Account cash = new Account("1100", "1000", "현금", "Cash", null, false);
            assertThat(cash.type()).isEqualTo(AccountType.ASSET);

            for (java.lang.reflect.Method method : Account.class.getMethods()) {
                assertThat(method.getName())
                        .as("changing an account's type would silently reclassify history")
                        .isNotEqualTo("setType");
            }
        }

        @Test
        @DisplayName("giving an account a child stops it accepting postings")
        void parentsAreNotPostable() {
            Account parent = new Account("1000", null, "유동자산", "Current assets", null, false);
            assertThat(parent.isPostable()).isTrue();

            parent.markAsParent();
            assertThat(parent.isPostable()).isFalse();
            assertThat(parent.postingRefusalReason())
                    .contains("subtotal")
                    .contains("no longer equal the sum");
        }

        @Test
        @DisplayName("a retired account keeps its history and takes no new postings")
        void retirementPreservesHistory() {
            Account old = new Account("5900", "5000", "구 비용계정", "Old expense", null, false);
            old.retire();
            assertThat(old.isPostable()).isFalse();
            assertThat(old.postingRefusalReason()).contains("historical figures are unchanged");
        }

        @Test
        @DisplayName("a contra account carries the opposite normal balance to its type")
        void contraAccounts() {
            // Accumulated depreciation sits under an asset and nets against it
            // rather than inflating the section.
            Account depreciation = new Account("1590", "1500", "감가상각누계액",
                    "Accumulated depreciation", null, true);
            assertThat(depreciation.type()).isEqualTo(AccountType.ASSET);
            assertThat(depreciation.isDebitNormal())
                    .as("a contra asset is credit-normal")
                    .isFalse();

            Account cash = new Account("1100", "1000", "현금", "Cash", null, false);
            assertThat(cash.isDebitNormal()).isTrue();
        }

        @Test
        @DisplayName("an id without a known type prefix is refused")
        void unknownPrefixRefused() {
            assertThatThrownBy(() -> new Account("9100", null, "?", "?", null, false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("known type prefix");
        }
    }

    @Nested
    @DisplayName("corrections")
    class Corrections {

        private Entry entry() {
            return Entry.post("e1", "book", WHEN, "비품 구입", postings(
                    Posting.debit("5100", Amount.parse("1000000")),
                    Posting.credit("1100", Amount.parse("1000000"))), null);
        }

        @Test
        @DisplayName("a void hides an entry from reports but keeps it in the journal")
        void voidHidesFromReportsOnly() {
            Entry entry = entry();
            entry.voidEntry("잘못된 거래처로 입력되었습니다.", "acc-1", WHEN);

            assertThat(entry.status()).isEqualTo(Entry.EntryStatus.VOID);
            assertThat(entry.isReportable())
                    .as("voided entries are excluded from all reports")
                    .isFalse();
            assertThat(entry.revisions()).hasSize(1);
            assertThat(entry.revisions().get(0).preStateSnapshot())
                    .as("the journal keeps what was there before")
                    .contains("5100");
        }

        @Test
        @DisplayName("voiding without a reason is refused")
        void voidNeedsReason() {
            assertThatThrownBy(() -> entry().voidEntry("  ", "acc-1", WHEN))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("indistinguishable from a mistake");
        }

        @Test
        @DisplayName("revisions are numbered and keep the pre-state")
        void revisionsAreNumbered() {
            Entry entry = entry();
            entry.recordRevision("금액 오기 수정", "acc-1", WHEN);
            entry.recordRevision("적요 수정", "acc-1", WHEN);

            assertThat(entry.revisions()).hasSize(2);
            assertThat(entry.revisions().get(0).number()).isEqualTo(1);
            assertThat(entry.revisions().get(1).number()).isEqualTo(2);
        }

        @Test
        @DisplayName("nothing is ever hard-deleted: there is no delete method")
        void noHardDelete() {
            for (java.lang.reflect.Method method : Entry.class.getMethods()) {
                assertThat(method.getName())
                        .as("a ledger never loses a row")
                        .doesNotStartWith("delete")
                        .doesNotStartWith("remove");
            }
        }

        @Test
        @DisplayName("a draft entry is excluded from reports but must still balance")
        void draftsAreExcludedButBalanced() {
            Entry draft = Entry.draft("e2", "book", WHEN, "작성중", postings(
                    Posting.debit("5100", Amount.parse("100")),
                    Posting.credit("1100", Amount.parse("100"))));
            assertThat(draft.isReportable()).isFalse();

            assertThatThrownBy(() -> Entry.draft("e3", "book", WHEN, "작성중", postings(
                    Posting.debit("5100", Amount.parse("100")),
                    Posting.credit("1100", Amount.parse("90")))))
                    .as("a draft that could never post is not worth keeping")
                    .isInstanceOf(Entry.UnbalancedEntryException.class);
        }
    }

    @Nested
    @DisplayName("currencies")
    class Currencies {

        @Test
        @DisplayName("displayDecimals never limits storage")
        void displayDecimalsArePresentationOnly() {
            Currency krw = Currency.krw();
            assertThat(krw.displayDecimals()).isZero();

            Amount fractional = Amount.parse("1000.5");
            assertThat(krw.formatForDisplay(fractional)).isEqualTo("1001");
            assertThat(fractional.toExactString()).isEqualTo("1000.5");
        }

        @Test
        @DisplayName("a currency may be any unit of account, not only money")
        void anyUnitOfAccount() {
            // Share counts, commodities, carbon credits. Nothing assumes money.
            Currency shares = new Currency("SHARES", "주식 수", "Share count", null, 0);
            assertThat(shares.formatForDisplay(Amount.parse("1500"))).isEqualTo("1500");
        }

        @Test
        void seededCurrencies() {
            assertThat(Currency.krw().displayDecimals()).isZero();
            assertThat(Currency.usd().displayDecimals()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("postings")
    class Postings {

        @Test
        @DisplayName("a zero posting is refused")
        void zeroPostingRefused() {
            assertThatThrownBy(() -> Posting.of("1100", Amount.ZERO))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("leave it out");
        }

        @Test
        @DisplayName("a foreign-currency posting without a rate is refused")
        void fxNeedsARate() {
            assertThatThrownBy(() -> Posting.foreignCurrency("5100", Amount.parse("1000"), "USD",
                    Amount.parse("1320000"), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("never looks one up");
        }

        @Test
        @DisplayName("the 거래처 dimension is carried on the posting")
        void clientDimension() {
            Posting posting = Posting.debit("1200", Amount.parse("500000"))
                    .withClient("client-42");
            assertThat(posting.clientId()).isEqualTo("client-42");
            assertThat(posting.baseAmount()).isEqualTo(Amount.parse("500000"));
        }
    }
}
