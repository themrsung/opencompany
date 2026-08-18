package com.coreintra.app.mcp;

import com.coreintra.compat.Immutables;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Every tool this server offers, and who is allowed to see which.
 *
 * <p>Tools are collected by Spring from the context, so adding a capability is
 * adding a bean — there is no central list to forget to update, which is how a
 * capability ends up reachable over REST and not over MCP, or the reverse.
 *
 * <h2>The write scope is a visibility rule, not just a check</h2>
 *
 * <p>§10 requires write tools to need explicit opt-in on the token. That is
 * enforced twice here, deliberately: a token without {@code mcp:write} does not
 * see write tools in {@code tools/list}, <em>and</em> a direct
 * {@code tools/call} for one is refused as though the tool did not exist. The
 * visibility half is what stops a model burning its turns retrying something it
 * can never be allowed to do; the refusal half is what makes it safe.
 */
@Component
public class McpToolRegistry {

    /** The scope an API key must carry before write tools become visible. */
    public static final String WRITE_SCOPE = "mcp:write";

    private final Map<String, McpTool> byName;

    /**
     * @param tools every {@link McpTool} bean. An {@link ObjectProvider} rather
     *              than a plain {@code List} because Spring treats an empty
     *              collection as "no candidate bean" and refuses to start —
     *              which would mean an installation with the accounting module
     *              switched off and no other tools registered could not boot.
     */
    public McpToolRegistry(ObjectProvider<McpTool> tools) {
        Map<String, McpTool> collected = new LinkedHashMap<String, McpTool>();
        for (McpTool tool : tools.orderedStream().collect(java.util.stream.Collectors.toList())) {
            McpTool clash = collected.put(tool.name(), tool);
            if (clash != null) {
                // Two tools under one name would make which capability runs
                // depend on bean ordering. Refuse to start instead.
                throw new IllegalStateException("two MCP tools are both named '" + tool.name()
                        + "': " + clash.getClass().getName() + " and " + tool.getClass().getName());
            }
            if (tool.requiredPermission().isWildcard()) {
                throw new IllegalStateException("MCP tool '" + tool.name()
                        + "' declares a wildcard permission. A tool checks one concrete "
                        + "permission, so that the explainer can say why it was allowed.");
            }
        }
        this.byName = Immutables.mapCopyOf(collected);
    }

    /** Tools the holder of these scopes may see. */
    public List<McpTool> visibleTo(java.util.Set<String> tokenScopes) {
        boolean mayWrite = tokenScopes != null && tokenScopes.contains(WRITE_SCOPE);
        List<McpTool> visible = new ArrayList<McpTool>();
        for (McpTool tool : byName.values()) {
            if (!tool.writes() || mayWrite) {
                visible.add(tool);
            }
        }
        return Immutables.copyOf(visible);
    }

    /**
     * @return null when the tool does not exist <em>or</em> is invisible to these
     *         scopes. The caller reports both the same way, so a read-scoped
     *         token cannot use error messages to enumerate the write tools.
     */
    public McpTool find(String name, java.util.Set<String> tokenScopes) {
        McpTool tool = byName.get(name);
        if (tool == null) {
            return null;
        }
        if (tool.writes() && (tokenScopes == null || !tokenScopes.contains(WRITE_SCOPE))) {
            return null;
        }
        return tool;
    }

    public int size() {
        return byName.size();
    }
}
