package com.coreintra.app.mcp.tools.org;

import com.coreintra.compat.Texts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * The small amount of plumbing every org tool shares.
 *
 * <h2>Why the argument readers throw</h2>
 *
 * <p>A model that omits a required argument, or sends a date as "next Tuesday",
 * has made a mistake it can correct — and {@code McpProtocol} turns an
 * {@link IllegalArgumentException} into an error <em>result</em> rather than a
 * protocol error, which is what tells the model to try again instead of
 * reporting the server as broken. Returning a silent default would be worse than
 * useless here: a business date quietly defaulted to today is how a tool answers
 * confidently about the wrong month.
 *
 * <p>{@code businessDate} is the exception, and deliberately so: it is optional
 * everywhere and defaults to today, because most questions are about now and
 * requiring a date on every call would make the tools tedious enough that a
 * model starts guessing one.
 */
public final class OrgToolSupport {

    /** Named once so every tool's schema and every tool's parser agree. */
    public static final String BUSINESS_DATE = "businessDate";

    private OrgToolSupport() {
    }

    /** An empty JSON Schema object, closed to unknown properties. */
    public static ObjectNode schema(ObjectMapper json) {
        ObjectNode schema = json.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties");
        // Closed on purpose: a misspelled argument should fail loudly rather
        // than be ignored while the tool answers a different question.
        schema.put("additionalProperties", false);
        schema.putArray("required");
        return schema;
    }

    public static void text(ObjectNode schema, String name, String description) {
        property(schema, name, "string", description);
    }

    public static void flag(ObjectNode schema, String name, String description) {
        property(schema, name, "boolean", description);
    }

    public static void textArray(ObjectNode schema, String name, String description) {
        ObjectNode property = schema.with("properties").putObject(name);
        property.put("type", "array");
        property.putObject("items").put("type", "string");
        property.put("description", description);
    }

    /** Adds the optional business date every org question is answered as of. */
    public static void businessDate(ObjectNode schema) {
        text(schema, BUSINESS_DATE, "The business date to answer as of, YYYY-MM-DD. Defaults to "
                + "today. The org chart is historical: a check about a document dated in March "
                + "must be asked with March's date, or it resolves against today's org and is "
                + "confidently wrong.");
    }

    public static void required(ObjectNode schema, String... names) {
        ArrayNode required = (ArrayNode) schema.get("required");
        for (String name : names) {
            required.add(name);
        }
    }

    private static void property(ObjectNode schema, String name, String type, String description) {
        ObjectNode property = schema.with("properties").putObject(name);
        property.put("type", type);
        property.put("description", description);
    }

    /** @throws IllegalArgumentException when absent or blank, naming the argument */
    public static String requiredText(JsonNode arguments, String name) {
        String value = optionalText(arguments, name);
        if (value == null) {
            throw new IllegalArgumentException("'" + name + "' is required");
        }
        return value;
    }

    /** @return null when absent, blank or JSON null */
    public static String optionalText(JsonNode arguments, String name) {
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

    public static boolean flag(JsonNode arguments, String name, boolean fallback) {
        if (arguments == null) {
            return fallback;
        }
        JsonNode node = arguments.get(name);
        return node == null || node.isNull() ? fallback : node.asBoolean(fallback);
    }

    public static List<String> textList(JsonNode arguments, String name) {
        List<String> values = new ArrayList<String>();
        if (arguments == null) {
            return values;
        }
        JsonNode node = arguments.get(name);
        if (node == null || node.isNull()) {
            return values;
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("'" + name + "' must be an array of strings");
        }
        for (JsonNode element : node) {
            if (!element.isNull()) {
                values.add(element.asText());
            }
        }
        return values;
    }

    /** The date to answer as of; today when the caller did not say. */
    public static LocalDate businessDate(JsonNode arguments) {
        String value = optionalText(arguments, BUSINESS_DATE);
        if (value == null) {
            return LocalDate.now();
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "'" + BUSINESS_DATE + "' must be YYYY-MM-DD; got '" + value + "'");
        }
    }

    /** A date, or null, as text — so a tool never renders "null" into its answer. */
    public static String text(LocalDate value) {
        return value == null ? null : value.toString();
    }
}
