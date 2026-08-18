package com.coreintra.app.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coreintra.compat.Immutables;
import com.coreintra.core.permission.DefaultPermissionEvaluator;
import com.coreintra.core.permission.GrantDirectory;
import com.coreintra.core.permission.OrgDirectory;
import com.coreintra.core.permission.PermissionDeniedException;
import com.coreintra.core.permission.PermissionGrant;
import com.coreintra.core.permission.PermissionKey;
import com.coreintra.core.permission.PermissionPrincipal;
import com.coreintra.core.permission.PermissionTarget;
import com.coreintra.core.permission.PrincipalOrgState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The MCP surface, and the one property that matters most about it: a
 * read-scoped token cannot see, call, or learn of the existence of a write tool.
 */
class McpProtocolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Set<String> READ_ONLY = Immutables.setOf("mcp:read");
    private static final Set<String> READ_WRITE = Immutables.setOf("mcp:read", "mcp:write");

    private static final PermissionPrincipal CALLER =
            PermissionPrincipal.serviceAccount("svc-1", "보고서 봇", READ_ONLY);

    private McpProtocol protocolWith(McpTool... tools) {
        return new McpProtocol(JSON, new McpToolRegistry(provider(tools)), "ACME 인트라넷");
    }

    private static ObjectNode request(String method) {
        ObjectNode node = JSON.createObjectNode();
        node.put("jsonrpc", "2.0");
        node.put("id", 1);
        node.put("method", method);
        return node;
    }

    private static ObjectNode call(String toolName) {
        ObjectNode node = request("tools/call");
        node.putObject("params").put("name", toolName).putObject("arguments");
        return node;
    }

    @Nested
    @DisplayName("handshake")
    class Handshake {

        @Test
        @DisplayName("advertises a protocol version and does not promise change notifications it cannot send")
        void initialize() {
            ObjectNode response = protocolWith().handle(CALLER, READ_ONLY, request("initialize"));

            JsonNode result = response.path("result");
            assertThat(result.path("protocolVersion").asText()).isEqualTo(McpProtocol.PROTOCOL_VERSION);
            assertThat(result.path("capabilities").path("tools").path("listChanged").asBoolean()).isFalse();
            assertThat(result.path("serverInfo").path("name").asText()).isEqualTo("ACME 인트라넷");
        }

        @Test
        @DisplayName("the instructions tell a model the two things it will otherwise get wrong")
        void instructionsCoverMoneyAndTime() {
            ObjectNode response = protocolWith().handle(CALLER, READ_ONLY, request("initialize"));

            String instructions = response.path("result").path("instructions").asText();
            assertThat(instructions).contains("exact decimal strings");
            assertThat(instructions).contains("-24:00:00");
        }

        @Test
        @DisplayName("a notification is not answered at all")
        void notificationsGetNoReply() {
            ObjectNode notification = JSON.createObjectNode();
            notification.put("jsonrpc", "2.0");
            notification.put("method", "notifications/initialized");

            assertThat(protocolWith().handle(CALLER, READ_ONLY, notification)).isNull();
        }

        @Test
        @DisplayName("an unknown method is a protocol error, because the server could not process it")
        void unknownMethod() {
            ObjectNode response = protocolWith().handle(CALLER, READ_ONLY, request("tools/delete"));

            assertThat(response.path("error").path("code").asInt()).isEqualTo(-32_601);
        }
    }

    @Nested
    @DisplayName("write tools need the scope, and are invisible without it")
    class WriteScope {

        private final McpTool read = new FakeTool("approval_list_inbox", false,
                "accounting.entry", "read");
        private final McpTool write = new FakeTool("accounting_post_entry", true,
                "accounting.entry", "post");

        @Test
        @DisplayName("a read-scoped token is not shown the write tool")
        void hiddenFromList() {
            ObjectNode response = protocolWith(read, write)
                    .handle(CALLER, READ_ONLY, request("tools/list"));

            assertThat(names(response)).containsExactly("approval_list_inbox");
        }

        @Test
        @DisplayName("a write-scoped token is shown both")
        void visibleWithScope() {
            ObjectNode response = protocolWith(read, write)
                    .handle(CALLER, READ_WRITE, request("tools/list"));

            assertThat(names(response)).containsExactlyInAnyOrder("approval_list_inbox", "accounting_post_entry");
        }

        @Test
        @DisplayName("calling a hidden write tool is refused as though it did not exist")
        void callingHiddenToolLooksLikeAbsence() {
            ObjectNode hidden = protocolWith(read, write)
                    .handle(CALLER, READ_ONLY, call("accounting_post_entry"));
            ObjectNode absent = protocolWith(read, write)
                    .handle(CALLER, READ_ONLY, call("no_such_tool"));

            // Identical answers, so a read-scoped token cannot enumerate the
            // write tools by probing for a different error.
            assertThat(hidden.path("error").path("code").asInt())
                    .isEqualTo(absent.path("error").path("code").asInt());
            assertThat(hidden.path("error").path("message").asText()).contains("accounting_post_entry");
        }

        @Test
        @DisplayName("every description states the permission and whether it writes")
        void descriptionsCarryTheTwoRequiredFacts() {
            ObjectNode response = protocolWith(read, write)
                    .handle(CALLER, READ_WRITE, request("tools/list"));

            for (JsonNode tool : response.path("result").path("tools")) {
                assertThat(tool.path("description").asText())
                        .contains("Requires permission:")
                        .contains("Writes:");
            }
        }

        @Test
        @DisplayName("readOnlyHint matches the tool's own answer")
        void annotationsAgree() {
            ObjectNode response = protocolWith(read, write)
                    .handle(CALLER, READ_WRITE, request("tools/list"));

            for (JsonNode tool : response.path("result").path("tools")) {
                boolean readOnly = tool.path("annotations").path("readOnlyHint").asBoolean();
                assertThat(readOnly).isEqualTo(!"accounting_post_entry".equals(tool.path("name").asText()));
            }
        }
    }

    @Nested
    @DisplayName("failures land on the right side of the protocol boundary")
    class Failures {

        @Test
        @DisplayName("a permission denial is a tool error, not a protocol error")
        void denialIsAToolError() {
            McpTool denies = new FakeTool("hr_read_salary", false, "hr.compensation", "read") {
                @Override
                public McpToolResult call(PermissionPrincipal principal, JsonNode arguments) {
                    // A real denial from the real evaluator, rather than a
                    // hand-made exception: this asserts the shape the API layer
                    // will actually see, including the decision it carries.
                    throw denialFor(principal, requiredPermission());
                }
            };

            ObjectNode response = protocolWith(denies).handle(CALLER, READ_ONLY, call("hr_read_salary"));

            // The server is fine; this call is not allowed. Reporting it as a
            // protocol error would tell the client the server is broken.
            assertThat(response.has("error")).isFalse();
            assertThat(response.path("result").path("isError").asBoolean()).isTrue();
            assertThat(response.path("result").path("_meta").path("code").asText())
                    .isEqualTo("permission_denied");
            assertThat(response.path("result").path("content").get(0).path("text").asText())
                    .contains("hr.compensation:read");
        }

        @Test
        @DisplayName("an unexpected fault is a protocol error, so it is not mistaken for a refusal")
        void faultsPropagate() {
            McpTool broken = new FakeTool("broken", false, "hr.employee", "read") {
                @Override
                public McpToolResult call(PermissionPrincipal principal, JsonNode arguments) {
                    throw new IllegalStateException("connection reset");
                }
            };

            ObjectNode response = protocolWith(broken).handle(CALLER, READ_ONLY, call("broken"));

            assertThat(response.path("error").path("code").asInt()).isEqualTo(-32_603);
        }

        @Test
        @DisplayName("a malformed request does not reach a tool")
        void malformedRequest() {
            assertThat(protocolWith().handle(CALLER, READ_ONLY, JSON.createArrayNode())
                    .path("error").path("code").asInt()).isEqualTo(-32_700);
        }
    }

    @Nested
    @DisplayName("the registry refuses to start on a mistake rather than resolving it at runtime")
    class RegistryGuards {

        @Test
        @DisplayName("two tools with one name would make behaviour depend on bean ordering")
        void duplicateNames() {
            assertThatThrownBy(() -> new McpToolRegistry(provider(
                    new FakeTool("same", false, "hr.employee", "read"),
                    new FakeTool("same", false, "hr.employee", "read"))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("both named");
        }

        @Test
        @DisplayName("a wildcard permission would make the explainer unable to say why")
        void wildcardPermission() {
            assertThatThrownBy(() -> new McpToolRegistry(provider(
                    new FakeTool("wild", false, "accounting.*", "post"))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("wildcard");
        }

        @Test
        @DisplayName("an installation with no tools at all still starts")
        void emptyRegistryStarts() {
            assertThat(new McpToolRegistry(provider()).size()).isZero();
        }
    }

    /**
     * A genuine denial, produced by the real evaluator against an org with
     * nothing in it. Deny-by-default means an empty directory refuses
     * everything, which is exactly the decision object under test here.
     */
    private static PermissionDeniedException denialFor(PermissionPrincipal principal,
            PermissionKey key) {
        OrgDirectory emptyOrg = new OrgDirectory() {
            @Override
            public PrincipalOrgState resolve(PermissionPrincipal who, LocalDate asOf) {
                return PrincipalOrgState.none(who.employeeId());
            }

            @Override
            public boolean isInSubtree(String ancestorId, String candidateDescendantId, LocalDate asOf) {
                return false;
            }
        };
        GrantDirectory noGrants = new GrantDirectory() {
            @Override
            public List<PermissionGrant> grantsFor(PermissionPrincipal who, PrincipalOrgState orgState) {
                return Immutables.listOf();
            }
        };
        PermissionTarget target = PermissionTarget.builder()
                .companyId("acme")
                .asOfBusinessDate(LocalDate.of(2026, 8, 30))
                .description("salary history")
                .build();
        try {
            new DefaultPermissionEvaluator(emptyOrg, noGrants).check(principal, key, target).orThrow();
        } catch (PermissionDeniedException denied) {
            return denied;
        }
        throw new AssertionError("deny by default did not deny, which is the whole model failing");
    }

    private static List<String> names(ObjectNode response) {
        List<String> found = new ArrayList<String>();
        for (JsonNode tool : response.path("result").path("tools")) {
            found.add(tool.path("name").asText());
        }
        return found;
    }

    /** A tool that answers with its own name, so a call can be told apart from a listing. */
    private static class FakeTool implements McpTool {
        private final String name;
        private final boolean writes;
        private final PermissionKey permission;

        FakeTool(String name, boolean writes, String resource, String action) {
            this.name = name;
            this.writes = writes;
            this.permission = PermissionKey.of(resource, action);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String summary() {
            return "Fake tool " + name + ".";
        }

        @Override
        public PermissionKey requiredPermission() {
            return permission;
        }

        @Override
        public boolean writes() {
            return writes;
        }

        @Override
        public ObjectNode inputSchema() {
            ObjectNode schema = JSON.createObjectNode();
            schema.put("type", "object");
            schema.putObject("properties");
            return schema;
        }

        @Override
        public McpToolResult call(PermissionPrincipal principal, JsonNode arguments) {
            return McpToolResult.text("called " + name);
        }
    }

    /**
     * The smallest {@link ObjectProvider} that serves the registry.
     *
     * <p>Spring's own default {@code orderedStream()} throws, so it has to be
     * overridden; the rest exist only to satisfy the interface. Writing this by
     * hand is cheaper than standing up a context to construct one class.
     */
    private static ObjectProvider<McpTool> provider(final McpTool... tools) {
        final List<McpTool> all = Immutables.copyOf(Arrays.asList(tools));
        return new ObjectProvider<McpTool>() {
            @Override
            public Stream<McpTool> orderedStream() {
                return all.stream();
            }

            @Override
            public Stream<McpTool> stream() {
                return all.stream();
            }

            @Override
            public McpTool getObject() throws BeansException {
                return all.get(0);
            }

            @Override
            public McpTool getObject(Object... args) throws BeansException {
                return getObject();
            }

            @Override
            public McpTool getIfAvailable() throws BeansException {
                return all.isEmpty() ? null : all.get(0);
            }

            @Override
            public McpTool getIfUnique() throws BeansException {
                return all.size() == 1 ? all.get(0) : null;
            }
        };
    }
}
