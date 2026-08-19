package com.coreintra.app.mcp;

import com.coreintra.app.api.permission.CurrentPrincipal;
import com.coreintra.core.permission.PermissionPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The MCP endpoint.
 *
 * <p>One route, because MCP is JSON-RPC and the method lives in the body. It
 * sits outside {@code /api/v1} on purpose: the REST surface is versioned by us,
 * whereas this one is versioned by the protocol, and pinning an MCP revision to
 * an API version would make both harder to move.
 *
 * <p>Authentication is the ordinary one. The same filter that resolves a browser
 * session resolves the scoped API key here, so an MCP client is not a special
 * kind of caller — it is a service account, audited as one, and judged by the
 * same evaluator. There is no anonymous MCP access, so this asks for the
 * principal rather than tolerating its absence.
 */
@RestController
@RequestMapping("/mcp")
@Tag(name = "MCP", description = "Model Context Protocol endpoint, exposing the same "
        + "capabilities as the REST API and governed by the same permissions.")
public class McpController {

    private final McpProtocol protocol;
    private final CurrentPrincipal current;

    public McpController(McpProtocol protocol, CurrentPrincipal current) {
        this.protocol = protocol;
        this.current = current;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "JSON-RPC 2.0 over HTTP",
            description = "Supports initialize, tools/list, tools/call and ping. Write tools "
                    + "require the mcp:write scope on the key and are invisible without it.")
    public ResponseEntity<ObjectNode> rpc(@RequestBody JsonNode request) {
        PermissionPrincipal principal = current.require();
        ObjectNode response = protocol.handle(principal, principal.apiKeyScopes(), request);
        if (response == null) {
            // JSON-RPC notifications take no reply. 202 rather than 204 because
            // the server has accepted the message, not completed a resource
            // operation, and some clients treat 204 as "nothing happened".
            return ResponseEntity.accepted().build();
        }
        return ResponseEntity.ok(response);
    }
}
