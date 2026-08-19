package com.coreintra.app.mcp.tools.accounting;

import com.coreintra.app.mcp.McpTool;
import com.coreintra.app.mcp.McpToolResult;
import com.coreintra.core.permission.PermissionPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * What every accounting tool has in common: a schema to declare and a result to serialise.
 *
 * <h2>The tools return the REST DTOs, deliberately</h2>
 *
 * <p>Each subclass calls the same service method a controller calls and then serialises the same
 * response type. That is what makes "one surface, three doors" true in practice rather than in
 * principle: an amount that is a string over REST is the same string over MCP because it is the
 * same object, and a field added to a report shows up on both without anybody remembering. A tool
 * that assembled its own JSON would drift, and the drift would be discovered by a model quietly
 * misreading a figure.
 *
 * <h2>Argument handling</h2>
 *
 * <p>Missing and malformed arguments raise {@link IllegalArgumentException}, which the protocol
 * turns into a tool error rather than a protocol error — a failure the model can read and act on,
 * not a broken server. Dates are ISO {@code YYYY-MM-DD}. Business instants, where a tool takes
 * one, are the wire form {@code YYYY-MM-DDT[-]HH:MM:SS.mmm}: no timezone, no trailing Z, and hours
 * outside 0..23 are ordinary rather than an error.
 */
abstract class AccountingTool implements McpTool {

    protected final ObjectMapper json;

    protected AccountingTool(ObjectMapper json) {
        this.json = json;
    }

    /** An object schema whose properties the subclass fills in. */
    protected ObjectNode schema(String... required) {
        ObjectNode root = json.createObjectNode();
        root.put("type", "object");
        root.putObject("properties");
        ArrayNode names = root.putArray("required");
        for (String name : required) {
            names.add(name);
        }
        // A model that invents an argument has misread the schema, and silently ignoring it hides
        // that from everybody including the model.
        root.put("additionalProperties", false);
        return root;
    }

    protected static ObjectNode property(ObjectNode schema, String name, String type,
            String description) {
        ObjectNode property = ((ObjectNode) schema.get("properties")).putObject(name);
        property.put("type", type);
        property.put("description", description);
        return property;
    }

    /** The book every accounting tool works within. */
    protected ObjectNode withBookId(ObjectNode schema) {
        property(schema, "book", "string",
                "The id of the set of books. Every account, entry and report belongs to exactly "
                        + "one book and they never mix.");
        return schema;
    }

    protected static String requiredText(JsonNode arguments, String field) {
        JsonNode value = arguments == null ? null : arguments.get(field);
        if (value == null || value.isNull() || value.asText().trim().isEmpty()) {
            throw new IllegalArgumentException("\"" + field + "\" is required");
        }
        return value.asText().trim();
    }

    protected static String optionalText(JsonNode arguments, String field) {
        JsonNode value = arguments == null ? null : arguments.get(field);
        return value == null || value.isNull() || value.asText().trim().isEmpty()
                ? null : value.asText().trim();
    }

    protected static LocalDate requiredDate(JsonNode arguments, String field) {
        return parseDate(requiredText(arguments, field), field);
    }

    /** @param fallback used when the caller left it out; never a silently different meaning */
    protected static LocalDate dateOr(JsonNode arguments, String field, LocalDate fallback) {
        String text = optionalText(arguments, field);
        return text == null ? fallback : parseDate(text, field);
    }

    protected static List<Integer> optionalInts(JsonNode arguments, String field) {
        JsonNode value = arguments == null ? null : arguments.get(field);
        if (value == null || value.isNull() || !value.isArray() || value.size() == 0) {
            return null;
        }
        List<Integer> numbers = new ArrayList<Integer>();
        for (JsonNode element : value) {
            numbers.add(Integer.valueOf(element.asInt()));
        }
        return numbers;
    }

    private static LocalDate parseDate(String text, String field) {
        try {
            return LocalDate.parse(text);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "\"" + field + "\" must be a calendar date, YYYY-MM-DD; got: " + text);
        }
    }

    /** Serialises a response DTO exactly as the REST surface would. */
    protected McpToolResult reply(Object body) {
        try {
            return McpToolResult.json(json.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialise the tool's answer", e);
        }
    }

    @Override
    public McpToolResult call(PermissionPrincipal principal, JsonNode arguments) {
        // The principal is passed straight through to the service, which decides. No tool holds a
        // repository and no tool decides anything itself; an ArchUnit rule fails the build on the
        // first one that tries.
        return run(principal, arguments == null ? json.createObjectNode() : arguments);
    }

    protected abstract McpToolResult run(PermissionPrincipal principal, JsonNode arguments);
}
