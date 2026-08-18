package com.coreintra.app.mcp;

import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One capability, exposed to an MCP client.
 *
 * <p>A tool is a <em>second client of a service</em>, never a second way into
 * the data. It calls the same service method a controller calls, with the same
 * {@link PermissionPrincipal}, and the decision is taken by the same evaluator.
 * There is no tool that reaches a repository, and an ArchUnit rule fails the
 * build if one appears.
 *
 * <h2>Read and write are separated at the type level</h2>
 *
 * <p>{@link #writes()} is not documentation. The registry uses it to hide write
 * tools entirely from a token that has not explicitly opted in, so a read-scoped
 * assistant does not see them, attempt them and report a permission error it
 * cannot act on. Hiding is better than failing here: a model that can see a tool
 * will keep trying it.
 */
public interface McpTool {

    /** Stable identifier, {@code snake_case}, namespaced by domain: {@code approval_list_inbox}. */
    String name();

    /**
     * What the tool does, the permission it requires, and whether it writes.
     *
     * <p>All three, in prose, because that is what the model reads. §10 requires
     * the permission and the write flag be stated; {@link #describe()} composes
     * them onto the end of this so no tool can forget.
     */
    String summary();

    /** The concrete permission checked before the call runs. Never a wildcard. */
    PermissionKey requiredPermission();

    /** True when calling this changes something. */
    boolean writes();

    /** JSON Schema for the arguments. */
    ObjectNode inputSchema();

    /**
     * Runs the tool.
     *
     * <p>The principal is supplied rather than read from a holder, so that a
     * tool cannot be invoked without one by construction.
     */
    McpToolResult call(PermissionPrincipal principal, JsonNode arguments);

    /** The description an MCP client sees: the summary, plus the two facts it must state. */
    default String describe() {
        return summary()
                + "\n\nRequires permission: " + requiredPermission()
                + "\nWrites: " + (writes() ? "yes — this changes data" : "no — read only");
    }
}
