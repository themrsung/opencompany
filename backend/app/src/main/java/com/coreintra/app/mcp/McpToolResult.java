package com.coreintra.app.mcp;

import com.coreintra.compat.Immutables;
import java.util.List;

/**
 * What a tool call returns.
 *
 * <p>MCP models a result as content blocks plus an {@code isError} flag. The
 * flag matters more than it looks: a tool failure reported as a protocol error
 * tells the client the *server* is broken, whereas a tool failure reported as an
 * error result tells the model that this particular call did not work and it may
 * try something else. A permission denial is the second kind.
 */
public final class McpToolResult {

    private final List<String> text;
    private final boolean error;
    /** Machine-readable, mirroring the REST {@code code}, so both surfaces agree. */
    private final String code;

    private McpToolResult(List<String> text, boolean error, String code) {
        this.text = Immutables.copyOf(text);
        this.error = error;
        this.code = code;
    }

    public static McpToolResult text(String body) {
        return new McpToolResult(Immutables.listOf(body), false, null);
    }

    public static McpToolResult json(String body) {
        return new McpToolResult(Immutables.listOf(body), false, null);
    }

    /**
     * A failure the model can reason about — a denial, a validation problem, a
     * missing row. Not for a bug in the server, which should propagate.
     */
    public static McpToolResult failure(String code, String message) {
        return new McpToolResult(Immutables.listOf(message), true, code);
    }

    public List<String> textBlocks() {
        return text;
    }

    public boolean isError() {
        return error;
    }

    public String code() {
        return code;
    }
}
