package com.coreintra.app.mcp.tools.accounting;

import com.coreintra.accounting.domain.Entry;
import com.coreintra.accounting.service.AccountingGate;
import com.coreintra.accounting.service.AccountingPermissions;
import com.coreintra.accounting.service.JournalService;
import com.coreintra.app.api.accounting.AccountingEnabled;
import com.coreintra.app.api.accounting.AccountingIdempotency;
import com.coreintra.app.api.accounting.JournalController;
import com.coreintra.app.mcp.McpToolResult;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * The one write tool in the accounting module: post an entry.
 *
 * <h2>Why this is the only one</h2>
 *
 * <p>Everything else that changes the books — opening an account, retiring a currency, closing a
 * year, voiding a batch — is a decision somebody makes deliberately and rarely, in a screen, with
 * the consequences in front of them. Posting an entry is the operation an assistant is genuinely
 * useful for: reading an invoice and writing it up. Exposing the rest as tools would be exposing
 * them because we could.
 *
 * <p>It is invisible to a token without {@code mcp:write}, and a direct call is refused as though
 * the tool did not exist. That is the registry's doing and it is enforced twice on purpose.
 *
 * <h2>The key is required, not optional</h2>
 *
 * <p>MCP has no headers, so the idempotency key is an argument — and it is mandatory for the same
 * reason it is mandatory over HTTP. A model that is unsure whether a call completed retries; a
 * ledger that accepted the retry has two entries and no way to know which was meant. Reusing a key
 * with a different body is refused rather than answered with the first result.
 *
 * <h2>Parsing is shared with REST, deliberately</h2>
 *
 * <p>The arguments are deserialised into the very request type the controller binds, so the two
 * doors validate identically: the same rejection of {@code 1e3}, the same insistence that a
 * posting names a side, the same entry-level 거래처 default. A second parser here would be a second
 * definition of what a valid entry is.
 */
@Component
@AccountingEnabled
public class PostEntryTool extends AccountingTool {

    private final JournalService journal;
    private final AccountingIdempotency idempotency;
    private final AccountingGate gate;

    public PostEntryTool(ObjectMapper json, JournalService journal,
            AccountingIdempotency idempotency, AccountingGate gate) {
        super(json);
        this.journal = journal;
        this.idempotency = idempotency;
        this.gate = gate;
    }

    @Override
    public String name() {
        return "accounting_post_entry";
    }

    @Override
    public String summary() {
        return "Write one journal entry into a set of books. Debits must equal credits EXACTLY in "
                + "the book's base currency — a rounding gap is a refusal, never absorbed, and the "
                + "refusal reports the difference. Amounts are exact decimal strings and must not "
                + "be parsed into numbers; thousands separators are accepted, and 1e3, .5 and 1. "
                + "are refused. Each posting names a side (debit or credit) and a positive "
                + "magnitude; the sign is applied here. postedAt is business time, "
                + "YYYY-MM-DDT[-]HH:MM:SS.mmm, with no timezone and no trailing Z; the hour may "
                + "run from -24 to 48 and ordering is by date first. The permission is checked as "
                + "of the entry's own business date, not today's. An idempotencyKey is required: "
                + "the same key with the same body replays the first answer, and the same key with "
                + "a different body is refused.";
    }

    @Override
    public PermissionKey requiredPermission() {
        return AccountingPermissions.ENTRY_POST;
    }

    @Override
    public boolean writes() {
        return true;
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = withBookId(schema("book", "idempotencyKey", "postedAt", "description",
                "postings"));
        property(schema, "idempotencyKey", "string",
                "A value you choose, at least 8 characters, identifying this one intent. Retrying "
                        + "with it replays the first answer instead of posting twice.");
        property(schema, "postedAt", "string",
                "Business instant, YYYY-MM-DDT[-]HH:MM:SS.mmm. No timezone, no trailing Z. Which "
                        + "business day it falls in decides which period reports it.");
        property(schema, "description", "string", "What happened, in the book's language.");
        property(schema, "clientId", "string",
                "Optional 거래처, applied to every posting that does not name its own.");
        property(schema, "draft", "boolean",
                "True to write it unposted: excluded from every report until it is posted. It "
                        + "still has to balance.");

        ObjectNode postings = property(schema, "postings", "array",
                "At least two lines. A single-sided entry cannot balance.");
        ObjectNode line = postings.putObject("items");
        line.put("type", "object");
        ObjectNode fields = line.putObject("properties");
        fields.putObject("accountId").put("type", "string")
                .put("description", "The account code. Only leaves accept postings.");
        fields.putObject("side").put("type", "string")
                .put("description", "\"debit\" or \"credit\".");
        fields.putObject("amount").put("type", "string")
                .put("description", "A positive magnitude, as an exact decimal string.");
        fields.putObject("currencyCode").put("type", "string")
                .put("description", "Only for a foreign-currency posting.");
        fields.putObject("baseAmount").put("type", "string")
                .put("description", "The base-currency equivalent, positive. Required with a "
                        + "currencyCode: this is what balances.");
        fields.putObject("rate").put("type", "string")
                .put("description", "The rate the business used. Required with a currencyCode. "
                        + "This system never looks a rate up.");
        fields.putObject("clientId").put("type", "string")
                .put("description", "Overrides the entry-level 거래처 for this line.");
        fields.putObject("memo").put("type", "string").put("description", "Free text.");
        ArrayNode required = line.putArray("required");
        required.add("accountId");
        required.add("side");
        required.add("amount");
        line.put("additionalProperties", false);
        return schema;
    }

    @Override
    protected McpToolResult run(PermissionPrincipal principal, JsonNode arguments) {
        final String bookId = requiredText(arguments, "book");
        final String key = requiredText(arguments, "idempotencyKey");
        final JournalController.PostEntryRequest request = read(arguments);
        String companyId = gate.book(bookId).companyId();

        AccountingIdempotency.Outcome outcome = idempotency.run(principal, companyId, key,
                "mcp:accounting_post_entry", request, 201, () -> {
                    Entry posted = request.isDraft()
                            ? journal.draft(principal, bookId, request.toNewEntry())
                            : journal.post(principal, bookId, request.toNewEntry());
                    return new JournalController.EntryResponse(posted,
                            journal.revisionsOf(principal, posted.id()));
                });
        return reply(outcome.body());
    }

    /**
     * Binds the arguments to the controller's request type.
     *
     * <p>{@code book} and {@code idempotencyKey} are not fields of it, and the request type
     * refuses unknown properties by the application's Jackson configuration, so they are removed
     * first. Copying the node rather than mutating the caller's is not fussiness: the same node is
     * the protocol's, and a tool that edited it would corrupt the next thing to read it.
     */
    private JournalController.PostEntryRequest read(JsonNode arguments) {
        ObjectNode body = arguments.deepCopy();
        body.remove("book");
        body.remove("idempotencyKey");
        try {
            return json.treeToValue(body, JournalController.PostEntryRequest.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("the entry could not be read: " + e.getMessage());
        }
    }
}
