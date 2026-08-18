package com.coreintra.app.mcp.tools.approval;

import com.coreintra.app.api.approval.ApiWire;
import com.coreintra.app.mcp.McpToolResult;
import com.coreintra.approval.domain.ApprovalLine;
import com.coreintra.approval.rules.EmploymentRules;
import com.coreintra.approval.service.ApprovalDocumentService;
import com.coreintra.approval.service.ApprovalLineTemplateService;
import com.coreintra.approval.service.ApproverDirectory;
import com.coreintra.attendance.service.AttendanceRecordService;
import com.coreintra.attendance.service.AttendanceStatusService;
import com.coreintra.attendance.service.LeaveService;
import com.coreintra.businesstime.BusinessInstant;
import com.coreintra.compat.Texts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.concurrent.Callable;

/**
 * Shared plumbing for the 결재 and 근태 tools.
 *
 * <h2>Turning a domain refusal into something a model can act on</h2>
 *
 * <p>{@code McpProtocol} maps a permission denial and a bad argument to tool
 * errors, and anything else to a JSON-RPC internal error — which tells the
 * client the <em>server</em> is broken. Almost every interesting refusal in this
 * module is neither: "this document was already submitted", "the 회수 comes
 * after the first approval", "the quorum is not met" are all
 * {@link IllegalStateException} subtypes that mean the call did not work and the
 * model should do something else. {@link #refusable} catches exactly those named
 * types and reports them as tool errors, so a model learns the rule rather than
 * concluding the intranet is down.
 *
 * <p>It catches named types only. A blanket {@code IllegalStateException} would
 * dress a genuine bug up as a business rule and hide it.
 */
public final class ApprovalToolSupport {

    /** Every tool answers as of a business date, and it is a parameter. */
    public static final String BUSINESS_DATE = "businessDate";

    private ApprovalToolSupport() {
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
        ObjectNode property = schema.with("properties").putObject(name);
        property.put("type", "string");
        property.put("description", description);
    }

    public static void required(ObjectNode schema, String... names) {
        ArrayNode required = (ArrayNode) schema.get("required");
        for (String name : names) {
            required.add(name);
        }
    }

    /** The business instant argument, described so a model does not invent a timezone. */
    public static void instant(ObjectNode schema, String name, String what) {
        text(schema, name, what + " as a business instant, " + ApiWire.INSTANT_PATTERN
                + " — for example " + ApiWire.INSTANT_EXAMPLE + ". No timezone and no "
                + "trailing Z; a Z is rejected rather than ignored. The clock runs from "
                + "-24:00:00 to +48:00:00 on one business day, so 03:00 during a shift that "
                + "began the previous evening is written as 27:00:00.000 on that evening's "
                + "date. Do not convert to UTC and do not roll over to the next date.");
    }

    public static void businessDate(ObjectNode schema) {
        text(schema, BUSINESS_DATE, "The business date to answer as of, YYYY-MM-DD. Defaults "
                + "to today. The org chart is historical, so a question about a document "
                + "dated in March must be asked with March's date or it is answered against "
                + "today's organisation and is confidently wrong.");
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

    public static BusinessInstant requiredInstant(JsonNode arguments, String name) {
        return ApiWire.instant(requiredText(arguments, name), name);
    }

    public static BigDecimal requiredDecimal(JsonNode arguments, String name) {
        BigDecimal value = ApiWire.amount(requiredText(arguments, name), name);
        if (value == null) {
            throw new IllegalArgumentException("'" + name + "' is required");
        }
        return value;
    }

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

    /**
     * Runs a tool body, reporting a domain refusal as a tool error.
     *
     * <p>The message is passed through untouched. The services phrase their
     * refusals in Korean and English and say what to do instead, which is
     * exactly what a model needs to choose its next move; paraphrasing them here
     * would lose the instruction.
     */
    public static McpToolResult refusable(Callable<McpToolResult> body) {
        try {
            return body.call();
        } catch (ApprovalDocumentService.DocumentStateException e) {
            return McpToolResult.failure("document_state_conflict", e.getMessage());
        } catch (ApprovalLine.ApprovalRuleException e) {
            return McpToolResult.failure("approval_rule_violated", e.getMessage());
        } catch (ApproverDirectory.UnresolvableRoleException e) {
            return McpToolResult.failure("approval_role_unresolvable", e.getMessage());
        } catch (ApprovalLineTemplateService.NoTemplateException e) {
            return McpToolResult.failure("approval_line_template_missing", e.getMessage());
        } catch (EmploymentRules.RepresentativeApprovalRequiredException e) {
            return McpToolResult.failure("representative_approval_required", e.getMessage());
        } catch (AttendanceRecordService.AttendanceRefusedException e) {
            return McpToolResult.failure("attendance_refused", e.getMessage());
        } catch (AttendanceStatusService.StatusDefinitionException e) {
            return McpToolResult.failure("attendance_status_definition", e.getMessage());
        } catch (LeaveService.LeaveRefusedException e) {
            return McpToolResult.failure("leave_refused", e.getMessage());
        } catch (RuntimeException e) {
            // Permission denials and bad arguments are handled by McpProtocol,
            // which distinguishes them properly; anything else is a real fault
            // and must not be disguised as a refusal.
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
