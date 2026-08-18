package com.coreintra.app.mcp.tools.documents;

import com.coreintra.compat.Texts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * The plumbing the document tools share.
 *
 * <h2>Why the readers throw</h2>
 *
 * <p>A model that omits a required argument has made a mistake it can correct,
 * and {@code McpProtocol} turns an {@link IllegalArgumentException} into an
 * error <em>result</em> rather than a protocol error — which tells the model to
 * try again instead of reporting the server as broken. A silent default would be
 * worse than useless: a document id guessed from a title is a confident answer
 * about the wrong document.
 *
 * <p>{@code businessDate} is the exception and defaults to today, because every
 * permission check needs one and requiring it on every call would make the tools
 * tedious enough that a model starts inventing dates.
 */
final class DocumentToolSupport {

    static final String BUSINESS_DATE = "businessDate";

    private DocumentToolSupport() {
    }

    /** An empty JSON Schema object, closed to unknown properties. */
    static ObjectNode schema(ObjectMapper json) {
        ObjectNode schema = json.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties");
        // Closed: a misspelled argument should fail loudly rather than be
        // ignored while the tool answers a different question.
        schema.put("additionalProperties", false);
        schema.putArray("required");
        return schema;
    }

    static void text(ObjectNode schema, String name, String description) {
        ObjectNode property = ((ObjectNode) schema.get("properties")).putObject(name);
        property.put("type", "string");
        property.put("description", description);
    }

    static void integer(ObjectNode schema, String name, String description) {
        ObjectNode property = ((ObjectNode) schema.get("properties")).putObject(name);
        property.put("type", "integer");
        property.put("description", description);
    }

    static void businessDate(ObjectNode schema) {
        text(schema, BUSINESS_DATE, "The business date to decide permissions as of, YYYY-MM-DD. "
                + "Defaults to today. The org chart is historical, so a question about a document "
                + "dated in March must be asked with March's date or it is answered against "
                + "today's org and is confidently wrong.");
    }

    static void required(ObjectNode schema, String... names) {
        ArrayNode required = (ArrayNode) schema.get("required");
        for (int i = 0; i < names.length; i++) {
            required.add(names[i]);
        }
    }

    /** @throws IllegalArgumentException when absent or blank, naming the argument */
    static String requiredText(JsonNode arguments, String name) {
        String value = optionalText(arguments, name);
        if (value == null) {
            throw new IllegalArgumentException("'" + name + "' is required");
        }
        return value;
    }

    /** @return null when absent, blank or JSON null */
    static String optionalText(JsonNode arguments, String name) {
        if (arguments == null) {
            return null;
        }
        JsonNode node = arguments.get(name);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return Texts.isBlank(value) ? null : Texts.strip(value);
    }

    static Integer optionalInteger(JsonNode arguments, String name) {
        if (arguments == null) {
            return null;
        }
        JsonNode node = arguments.get(name);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isNumber()) {
            throw new IllegalArgumentException("'" + name + "' must be a number");
        }
        return Integer.valueOf(node.asInt());
    }

    static LocalDate businessDate(JsonNode arguments) {
        String value = optionalText(arguments, BUSINESS_DATE);
        if (value == null) {
            return LocalDate.now();
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "'" + BUSINESS_DATE + "' must be YYYY-MM-DD, not \"" + value + "\"");
        }
    }
}
