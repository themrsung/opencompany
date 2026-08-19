package com.coreintra.app.mcp.tools.accounting;

import com.coreintra.accounting.domain.Account;
import com.coreintra.accounting.domain.ChartOfAccounts;
import com.coreintra.accounting.service.AccountingPermissions;
import com.coreintra.accounting.service.ChartOfAccountsService;
import com.coreintra.accounting.service.LedgerReportService;
import com.coreintra.app.api.accounting.AccountingEnabled;
import com.coreintra.app.api.accounting.AccountingReportsController;
import com.coreintra.app.api.accounting.ChartOfAccountsController;
import com.coreintra.app.mcp.McpToolResult;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The read tools: the chart of accounts, and every report.
 *
 * <p>Eight beans in one file because they are eight variations on one idea — name a book, name a
 * date, get a statement — and splitting them across eight files would hide how similar they are
 * without making any of them clearer. Each is still its own {@code @Component}, so the registry
 * collects them the way it collects everything else and there is no list to keep in step.
 *
 * <h2>All of them are read-only, and say so</h2>
 *
 * <p>{@code writes()} is false throughout, which means a read-scoped MCP token sees all of them.
 * The one write tool in this module lives in {@link PostEntryTool} and is invisible without
 * {@code mcp:write}. That separation is the point of §10: a model that can see a tool will keep
 * trying it, so a tool it can never be allowed to call should not appear.
 *
 * <h2>Amounts</h2>
 *
 * <p>Every figure these tools return is an exact decimal string, at every level of every response,
 * because they serialise the same DTOs the REST surface does. A model must not parse one into a
 * number: {@code 333333.33333333333333} does not survive a double, and nothing in the response
 * would say that it had not.
 */
final class LedgerReportTools {

    private LedgerReportTools() {
    }

    /** The chart of accounts, with inheritance already resolved. */
    @Component
    @AccountingEnabled
    public static class ChartOfAccountsTool extends AccountingTool {

        private final ChartOfAccountsService chart;

        public ChartOfAccountsTool(ObjectMapper json, ChartOfAccountsService chart) {
            super(json);
            this.chart = chart;
        }

        @Override
        public String name() {
            return "accounting_chart_of_accounts";
        }

        @Override
        public String summary() {
            return "Every account in a set of books, as a tree. Each account reports its own "
                    + "classification and category and the ones it resolves to from its nearest "
                    + "ancestor, so \"why is this account in the investing section\" is answerable "
                    + "from the response. Only leaves are postable; an account with children is a "
                    + "subtotal and postingRefusalReason says so. Retired accounts are listed - "
                    + "they still hold history - and take no new postings.";
        }

        @Override
        public PermissionKey requiredPermission() {
            return AccountingPermissions.ACCOUNT_READ;
        }

        @Override
        public boolean writes() {
            return false;
        }

        @Override
        public ObjectNode inputSchema() {
            ObjectNode schema = withBookId(schema("book"));
            property(schema, "businessDate", "string",
                    "YYYY-MM-DD. The date the permission is resolved as of. Defaults to today.");
            return schema;
        }

        @Override
        protected McpToolResult run(PermissionPrincipal principal, JsonNode arguments) {
            String bookId = requiredText(arguments, "book");
            LocalDate asOf = dateOr(arguments, "businessDate", LocalDate.now());
            ChartOfAccounts snapshot = chart.chart(principal, bookId, asOf);
            List<ChartOfAccountsController.AccountResponse> accounts =
                    new ArrayList<ChartOfAccountsController.AccountResponse>();
            for (Account account : snapshot.accounts()) {
                accounts.add(new ChartOfAccountsController.AccountResponse(account, snapshot));
            }
            return reply(accounts);
        }
    }

    /** Every report tool shares its permission, its book argument and its service. */
    abstract static class ReportTool extends AccountingTool {

        protected final LedgerReportService reports;

        protected ReportTool(ObjectMapper json, LedgerReportService reports) {
            super(json);
            this.reports = reports;
        }

        @Override
        public PermissionKey requiredPermission() {
            return AccountingPermissions.REPORT_READ;
        }

        @Override
        public boolean writes() {
            return false;
        }

        /** A report over a period: from and to, both inclusive, both required. */
        protected ObjectNode periodSchema() {
            ObjectNode schema = withBookId(schema("book", "from", "to"));
            property(schema, "from", "string", "YYYY-MM-DD. Inclusive.");
            property(schema, "to", "string", "YYYY-MM-DD. Inclusive. The permission is resolved "
                    + "as of this date, not today's.");
            return schema;
        }

        /** A report as of one date. */
        protected ObjectNode asOfSchema() {
            ObjectNode schema = withBookId(schema("book"));
            property(schema, "asOf", "string", "YYYY-MM-DD. Inclusive. Defaults to today. The "
                    + "permission is resolved as of this date, not today's.");
            return schema;
        }

        protected LocalDate asOf(JsonNode arguments) {
            return dateOr(arguments, "asOf", LocalDate.now());
        }
    }

    /** Trial balance. */
    @Component
    @AccountingEnabled
    public static class TrialBalanceTool extends ReportTool {

        public TrialBalanceTool(ObjectMapper json, LedgerReportService reports) {
            super(json, reports);
        }

        @Override
        public String name() {
            return "accounting_trial_balance";
        }

        @Override
        public String summary() {
            return "Debits and credits per account as of a business date, inclusive. Draft and "
                    + "voided entries contribute nothing. The 'balanced' flag is a data-integrity "
                    + "alarm and never a plug: every entry balances by construction, so false "
                    + "means something reached the tables without going through the engine. "
                    + "'imbalance' is the size of it. Report it; do not work around it.";
        }

        @Override
        public ObjectNode inputSchema() {
            return asOfSchema();
        }

        @Override
        protected McpToolResult run(PermissionPrincipal principal, JsonNode arguments) {
            LocalDate on = asOf(arguments);
            return reply(new AccountingReportsController.TrialBalanceResponse(
                    reports.trialBalance(principal, requiredText(arguments, "book"), on), on));
        }
    }

    /** Balance sheet. */
    @Component
    @AccountingEnabled
    public static class BalanceSheetTool extends ReportTool {

        public BalanceSheetTool(ObjectMapper json, LedgerReportService reports) {
            super(json, reports);
        }

        @Override
        public String name() {
            return "accounting_balance_sheet";
        }

        @Override
        public String summary() {
            return "Assets, liabilities and equity as of a business date. The only derived line is "
                    + "unclosedNetIncome - the result still sitting on the income and expense "
                    + "accounts - and it is reported separately rather than folded into equity. "
                    + "Closing batches are NOT excluded from it: a closed result has left those "
                    + "accounts, which is exactly what the line measures. 'balanced' is an alarm, "
                    + "never a balancing figure.";
        }

        @Override
        public ObjectNode inputSchema() {
            return asOfSchema();
        }

        @Override
        protected McpToolResult run(PermissionPrincipal principal, JsonNode arguments) {
            return reply(new AccountingReportsController.BalanceSheetResponse(
                    reports.balanceSheet(principal, requiredText(arguments, "book"),
                            asOf(arguments))));
        }
    }

    /** Income statement. */
    @Component
    @AccountingEnabled
    public static class IncomeStatementTool extends ReportTool {

        public IncomeStatementTool(ObjectMapper json, LedgerReportService reports) {
            super(json, reports);
        }

        @Override
        public String name() {
            return "accounting_income_statement";
        }

        @Override
        public String summary() {
            return "Income less expense for a period. Closing batches are excluded, so a year that "
                    + "has been closed still reports what it earned rather than reporting nil. "
                    + "Draft and voided entries contribute nothing.";
        }

        @Override
        public ObjectNode inputSchema() {
            return periodSchema();
        }

        @Override
        protected McpToolResult run(PermissionPrincipal principal, JsonNode arguments) {
            return reply(new AccountingReportsController.IncomeStatementResponse(
                    reports.incomeStatement(principal, requiredText(arguments, "book"),
                            requiredDate(arguments, "from"), requiredDate(arguments, "to"))));
        }
    }

    /** Cash flow. */
    @Component
    @AccountingEnabled
    public static class CashFlowTool extends ReportTool {

        public CashFlowTool(ObjectMapper json, LedgerReportService reports) {
            super(json, reports);
        }

        @Override
        public String name() {
            return "accounting_cash_flow";
        }

        @Override
        public String summary() {
            return "Cash in and out over a period, by the direct method: the actual movements of "
                    + "the accounts categorised CASH or CASH_EQUIVALENT, each attributed to the "
                    + "classification of the account on the other side of the entry. Sections are "
                    + "OPERATING, INVESTING, FINANCING, TAX, FX, OTHER and UNCLASSIFIED - the last "
                    + "for movements whose counterpart account has no classification, which are "
                    + "reported rather than assumed to be operating. openingCash plus the sections "
                    + "equals closingCash exactly; 'reconciles' is an alarm if it does not.";
        }

        @Override
        public ObjectNode inputSchema() {
            return periodSchema();
        }

        @Override
        protected McpToolResult run(PermissionPrincipal principal, JsonNode arguments) {
            return reply(new AccountingReportsController.CashFlowResponse(
                    reports.cashFlow(principal, requiredText(arguments, "book"),
                            requiredDate(arguments, "from"), requiredDate(arguments, "to"))));
        }
    }

    /** Statement of changes in equity. */
    @Component
    @AccountingEnabled
    public static class EquityStatementTool extends ReportTool {

        public EquityStatementTool(ObjectMapper json, LedgerReportService reports) {
            super(json, reports);
        }

        @Override
        public String name() {
            return "accounting_equity_statement";
        }

        @Override
        public String summary() {
            return "How total equity moved over a period. Equity here means what the balance sheet "
                    + "means: the equity accounts plus the net income not yet closed into them. "
                    + "The movements are netIncome (closing batches excluded), ownerMovements "
                    + "(capital, dividends) and closingAdjustments - the net effect of the closing "
                    + "batches, which is zero for a well-formed close because closing reclassifies "
                    + "equity rather than creating it. 'balanced' checks the roll-forward against "
                    + "assets less liabilities, computed independently.";
        }

        @Override
        public ObjectNode inputSchema() {
            return periodSchema();
        }

        @Override
        protected McpToolResult run(PermissionPrincipal principal, JsonNode arguments) {
            return reply(new AccountingReportsController.EquityStatementResponse(
                    reports.equityStatement(principal, requiredText(arguments, "book"),
                            requiredDate(arguments, "from"), requiredDate(arguments, "to"))));
        }
    }

    /** Receivables ageing. */
    @Component
    @AccountingEnabled
    public static class ReceivablesAgeingTool extends ReportTool {

        public ReceivablesAgeingTool(ObjectMapper json, LedgerReportService reports) {
            super(json, reports);
        }

        @Override
        public String name() {
            return "accounting_receivables_ageing";
        }

        @Override
        public String summary() {
            return "What is owed, by 거래처 and by age, as of a business date. Read off the general "
                    + "ledger rather than a sub-ledger, so it cannot disagree with the balance "
                    + "sheet. There is no invoice-level matching in this engine, so settlements "
                    + "are applied oldest-first per counterparty and account. Credits that settle "
                    + "nothing are reported as unappliedCredits, not as a negative age. "
                    + "Outstanding on postings that named no 거래처 is reported as 'unassigned' "
                    + "rather than dropped.";
        }

        @Override
        public ObjectNode inputSchema() {
            ObjectNode schema = asOfSchema();
            ObjectNode bands = property(schema, "bandDays", "array",
                    "Band boundaries in days, ascending. Defaults to [30, 60, 90]. There is always "
                            + "one more bucket than there are bands: the last is everything older.");
            bands.putObject("items").put("type", "integer");
            return schema;
        }

        @Override
        protected McpToolResult run(PermissionPrincipal principal, JsonNode arguments) {
            return reply(new AccountingReportsController.ReceivablesAgeingResponse(
                    reports.receivablesAgeing(principal, requiredText(arguments, "book"),
                            asOf(arguments), optionalInts(arguments, "bandDays"))));
        }
    }

    /** Prepaid schedules. */
    @Component
    @AccountingEnabled
    public static class PrepaidSchedulesTool extends ReportTool {

        public PrepaidSchedulesTool(ObjectMapper json, LedgerReportService reports) {
            super(json, reports);
        }

        @Override
        public String name() {
            return "accounting_prepaid_schedules";
        }

        @Override
        public String summary() {
            return "Prepayments as of a business date: what was capitalised, what has been "
                    + "recognised, what is still unexpired, and the instalments already posted "
                    + "against it. The scheduled instalments are entries that exist in the journal "
                    + "dated after the as-of date - not a forecast, which is why each carries its "
                    + "entry id. An unexpired balance with an empty schedule is a prepayment "
                    + "nobody has set an amortisation up for; 'unscheduled' totals those.";
        }

        @Override
        public ObjectNode inputSchema() {
            return asOfSchema();
        }

        @Override
        protected McpToolResult run(PermissionPrincipal principal, JsonNode arguments) {
            return reply(new AccountingReportsController.PrepaidSchedulesResponse(
                    reports.prepaidSchedules(principal, requiredText(arguments, "book"),
                            asOf(arguments))));
        }
    }
}
