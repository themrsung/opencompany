package com.coreintra.app.mcp;

import com.coreintra.core.permission.PermissionDeniedException;
import com.coreintra.core.permission.PermissionPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The JSON-RPC 2.0 subset that Model Context Protocol needs.
 *
 * <h2>Why this is hand-rolled</h2>
 *
 * <p>The official MCP Java SDK requires Java 17 and this backend is pinned to 8
 * (ADR 0001). The protocol surface a tool server actually needs is four methods
 * — {@code initialize}, {@code notifications/initialized}, {@code tools/list},
 * {@code tools/call} — plus {@code ping}, so implementing it is smaller than
 * carrying a shim, and it keeps the dependency surface small, which Boot 2.7
 * being past EOL makes worth caring about.
 *
 * <h2>Errors: two kinds, and they mean different things</h2>
 *
 * <ul>
 *   <li>A <b>protocol error</b> (JSON-RPC {@code error}) says the server could
 *       not process the request at all — unknown method, malformed params. The
 *       client should treat it as a fault.</li>
 *   <li>A <b>tool error</b> ({@code isError: true} inside a normal result) says
 *       this call did not work. A permission denial is this kind, so the model
 *       learns it may not do that and moves on instead of concluding the server
 *       is broken.</li>
 * </ul>
 */
@Component
public class McpProtocol {

    /** The revision of MCP this implements. Advertised in {@code initialize}. */
    public static final String PROTOCOL_VERSION = "2025-06-18";

    private static final int PARSE_ERROR = -32_700;
    private static final int INVALID_REQUEST = -32_600;
    private static final int METHOD_NOT_FOUND = -32_601;
    private static final int INVALID_PARAMS = -32_602;
    private static final int INTERNAL_ERROR = -32_603;

    private final ObjectMapper json;
    private final McpToolRegistry registry;
    private final String installationName;

    public McpProtocol(ObjectMapper json, McpToolRegistry registry,
            @org.springframework.beans.factory.annotation.Value("${coreintra.installation-name:CoreIntra}")
            String installationName) {
        this.json = json;
        this.registry = registry;
        this.installationName = installationName;
    }

    /**
     * Handles one request.
     *
     * @param principal   the authenticated caller; never null, because there is
     *                    no anonymous MCP access
     * @param tokenScopes the scopes on the presented token, which narrow what
     *                    the account may do and never widen it
     * @return the response, or null for a notification, which by JSON-RPC takes
     *         no reply at all
     */
    public ObjectNode handle(PermissionPrincipal principal, Set<String> tokenScopes, JsonNode request) {
        if (request == null || !request.isObject()) {
            return error(null, PARSE_ERROR, "Request must be a JSON-RPC object.");
        }
        JsonNode id = request.get("id");
        String method = request.path("method").asText(null);
        if (method == null) {
            return error(id, INVALID_REQUEST, "Missing 'method'.");
        }

        // A notification has no id and must not be answered.
        boolean notification = id == null || id.isNull();

        if ("notifications/initialized".equals(method)) {
            return null;
        }
        if (notification) {
            return null;
        }

        if ("initialize".equals(method)) {
            return result(id, initializeResult());
        }
        if ("ping".equals(method)) {
            return result(id, json.createObjectNode());
        }
        if ("tools/list".equals(method)) {
            return result(id, toolsList(tokenScopes));
        }
        if ("tools/call".equals(method)) {
            return toolsCall(id, principal, tokenScopes, request.path("params"));
        }
        return error(id, METHOD_NOT_FOUND, "Unknown method: " + method);
    }

    private ObjectNode initializeResult() {
        ObjectNode payload = json.createObjectNode();
        payload.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode capabilities = payload.putObject("capabilities");
        // listChanged is false: the tool set is fixed at boot by which beans
        // exist, so promising change notifications would be a lie.
        capabilities.putObject("tools").put("listChanged", false);
        ObjectNode info = payload.putObject("serverInfo");
        info.put("name", installationName);
        info.put("version", "1");
        payload.put("instructions",
                "Every tool here is governed by the same permissions as the web interface. "
                        + "A tool you cannot see is one your token is not scoped for; write "
                        + "tools require the mcp:write scope. Amounts are exact decimal "
                        + "strings and must not be parsed as numbers. Times are business "
                        + "instants: YYYY-MM-DDT[-]HH:MM:SS.mmm with no timezone, where the "
                        + "clock runs from -24:00:00 to +48:00:00 and ordering is by business "
                        + "date first, then offset.");
        return payload;
    }

    private ObjectNode toolsList(Set<String> tokenScopes) {
        ObjectNode payload = json.createObjectNode();
        ArrayNode tools = payload.putArray("tools");
        List<McpTool> visible = registry.visibleTo(tokenScopes);
        for (McpTool tool : visible) {
            ObjectNode entry = tools.addObject();
            entry.put("name", tool.name());
            entry.put("description", tool.describe());
            entry.set("inputSchema", tool.inputSchema());
            ObjectNode annotations = entry.putObject("annotations");
            annotations.put("readOnlyHint", !tool.writes());
            // Nothing here deletes; corrections are revisions and voids.
            annotations.put("destructiveHint", false);
        }
        return payload;
    }

    private ObjectNode toolsCall(JsonNode id, PermissionPrincipal principal, Set<String> tokenScopes,
            JsonNode params) {
        String name = params.path("name").asText(null);
        if (name == null) {
            return error(id, INVALID_PARAMS, "tools/call needs a 'name'.");
        }
        McpTool tool = registry.find(name, tokenScopes);
        if (tool == null) {
            // Deliberately the same answer for "does not exist" and "your token
            // may not see it", so a read-scoped token cannot enumerate the
            // write tools by probing.
            return error(id, METHOD_NOT_FOUND, "No such tool: " + name);
        }

        JsonNode arguments = params.has("arguments") ? params.get("arguments") : json.createObjectNode();
        try {
            return result(id, toolContent(tool.call(principal, arguments)));
        } catch (PermissionDeniedException e) {
            // A tool error, not a protocol error: the server is fine, this call
            // is not allowed, and the model should try something else.
            return result(id, toolContent(McpToolResult.failure("permission_denied",
                    e.getMessage() + " Required permission: " + e.requiredPermission())));
        } catch (IllegalArgumentException e) {
            return result(id, toolContent(McpToolResult.failure("invalid_argument", e.getMessage())));
        } catch (RuntimeException e) {
            // Genuinely unexpected. Surface it as a protocol error so it is not
            // mistaken for "this request was refused".
            return error(id, INTERNAL_ERROR, "The tool failed: " + e.getMessage());
        }
    }

    private ObjectNode toolContent(McpToolResult toolResult) {
        ObjectNode payload = json.createObjectNode();
        ArrayNode content = payload.putArray("content");
        for (String block : toolResult.textBlocks()) {
            ObjectNode item = content.addObject();
            item.put("type", "text");
            item.put("text", block);
        }
        if (toolResult.isError()) {
            payload.put("isError", true);
            ObjectNode meta = payload.putObject("_meta");
            meta.put("code", toolResult.code());
        }
        return payload;
    }

    private ObjectNode result(JsonNode id, ObjectNode payload) {
        ObjectNode response = json.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", payload);
        return response;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = json.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id == null ? json.nullNode() : id);
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return response;
    }
}
