package com.springaimcpservercommon.mcp.server;

import com.springaimcpservercommon.ai.tool.ToolCallScope;
import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.core.invocation.Channel;
import com.springaimcpservercommon.core.principal.DaiPrincipal;
import com.springaimcpservercommon.core.principal.SubjectType;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.security.authentication.TestingAuthenticationToken;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class McpProtocolHandlerTest {

    private final DaiPrincipal principal = new DaiPrincipal(UUID.randomUUID(), SubjectType.USER, "https://idp", "alice",
            "Alice", Set.of(), Set.of(), Map.of(), Map.of(), Classification.INTERNAL, null, Set.of("dai.mcp.read"));
    private final UUID workspace = UUID.randomUUID();
    private final McpProtocolHandler.Caller caller = new McpProtocolHandler.Caller(principal,
            new TestingAuthenticationToken("alice", "x"), workspace, null);
    private final List<McpRequestRecorder.McpCall> recorded = new ArrayList<>();
    private final List<ToolCallScope> scopes = new ArrayList<>();
    private List<ToolCallback> available = List.of();

    private McpProtocolHandler handler() {
        return new McpProtocolHandler((c, scope) -> {
            scopes.add(scope);
            return available;
        }, recorded::add, Clock.fixed(Instant.parse("2026-09-29T10:00:00Z"), ZoneOffset.UTC), "test-server", "1.0");
    }

    private static ToolCallback tool(String name, String schema, Function<String, String> body) {
        ToolDefinition definition = ToolDefinition.builder().name(name).description("Tool " + name)
                .inputSchema(schema).build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return body.apply(toolInput);
            }
        };
    }

    private static String request(String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"" + method + "\"" + (params == null ? "" : ",\"params\":" + params)
                + "}";
    }

    @Test
    void initializeEchoesASupportedVersionAndOtherwiseOffersTheNewest() {
        var ok = handler().handle(request("initialize", "{\"protocolVersion\":\"2025-06-18\"}"), caller);
        assertThat(ok.httpStatus()).isEqualTo(200);
        assertThat(ok.body()).contains("\"protocolVersion\":\"2025-06-18\"").contains("\"name\":\"test-server\"")
                .contains("\"listChanged\":false").contains("\"id\":7");

        var other = handler().handle(request("initialize", "{\"protocolVersion\":\"1999-01-01\"}"), caller);
        assertThat(other.body()).contains("\"protocolVersion\":\"2025-11-25\"");
        assertThat(recorded).extracting(McpRequestRecorder.McpCall::method).containsExactly("initialize", "initialize");
    }

    @Test
    void pingAnswersWithAnEmptyResult() {
        var reply = handler().handle(request("ping", null), caller);
        assertThat(reply.httpStatus()).isEqualTo(200);
        assertThat(reply.body()).contains("\"result\":{}");
    }

    @Test
    void toolsListIsSortedAndCarriesTheInputSchemaAsAnObject() {
        available = List.of(tool("zeta_tool", "{\"type\":\"object\"}", in -> "x"),
                tool("alpha_tool", "{\"type\":\"object\",\"properties\":{}}", in -> "x"));
        var reply = handler().handle(request("tools/list", null), caller);

        assertThat(reply.body()).contains("\"name\":\"alpha_tool\"");
        assertThat(reply.body().indexOf("alpha_tool")).isLessThan(reply.body().indexOf("zeta_tool"));
        assertThat(reply.body()).contains("\"inputSchema\":{\"properties\":{},\"type\":\"object\"}");
        assertThat(scopes).singleElement().satisfies(s -> {
            assertThat(s.channel()).isEqualTo(Channel.MCP);
            assertThat(s.mcpRequestId()).isEqualTo(recorded.getFirst().id());
        });
    }

    @Test
    void toolsCallReturnsTheToolOutputAsTextContent() {
        List<String> inputs = new ArrayList<>();
        available = List.of(tool("find_orders", "{}", in -> {
            inputs.add(in);
            return "{\"status\":\"ok\",\"count\":1}";
        }));
        var reply = handler().handle(request("tools/call",
                "{\"name\":\"find_orders\",\"arguments\":{\"customer\":\"c-1\"}}"), caller);

        assertThat(reply.httpStatus()).isEqualTo(200);
        assertThat(reply.body()).contains("\"isError\":false").contains("\"type\":\"text\"");
        assertThat(inputs).containsExactly("{\"customer\":\"c-1\"}");
        assertThat(recorded).singleElement().satisfies(c -> {
            assertThat(c.method()).isEqualTo("tools/call");
            assertThat(c.toolName()).isEqualTo("find_orders");
            assertThat(c.status()).isEqualTo(McpRequestRecorder.Status.OK);
            assertThat(c.jsonrpcId()).isEqualTo("7");
            assertThat(c.principalId()).isEqualTo(principal.principalId());
            assertThat(c.workspaceId()).isEqualTo(workspace);
        });
    }

    @Test
    void aToolThatReportsAnErrorIsMarkedAsAnErrorResult() {
        available = List.of(tool("find_orders", "{}", in -> "{\"status\":\"error\"}"));
        var reply = handler().handle(request("tools/call", "{\"name\":\"find_orders\"}"), caller);
        assertThat(reply.body()).contains("\"isError\":true");
    }

    @Test
    void aDeniedToolIsRecordedAsDenied() {
        available = List.of(tool("find_orders", "{}", in -> "{\"status\":\"not_permitted\"}"));
        handler().handle(request("tools/call", "{\"name\":\"find_orders\"}"), caller);
        assertThat(recorded).singleElement().extracting(McpRequestRecorder.McpCall::status)
                .isEqualTo(McpRequestRecorder.Status.DENIED);
    }

    @Test
    void anUnknownToolIsAnInvalidParamsErrorWithoutRevealingWhetherItExists() {
        var reply = handler().handle(request("tools/call", "{\"name\":\"hidden_tool\"}"), caller);
        assertThat(reply.body()).contains("\"code\":-32602").contains("Unknown tool");
        assertThat(recorded).singleElement().extracting(McpRequestRecorder.McpCall::errorCode).isEqualTo("unknown_tool");
    }

    @Test
    void aFailingToolNeverLeaksItsMessage() {
        available = List.of(tool("find_orders", "{}", in -> {
            throw new IllegalStateException("jdbc:postgresql://secret-host/db");
        }));
        var reply = handler().handle(request("tools/call", "{\"name\":\"find_orders\",\"arguments\":{}}"), caller);
        assertThat(reply.body()).contains("\"code\":-32603").doesNotContain("secret-host");
        assertThat(recorded).singleElement().extracting(McpRequestRecorder.McpCall::errorCode)
                .isEqualTo("internal_error");
    }

    @Test
    void argumentsMustBeAnObject() {
        available = List.of(tool("find_orders", "{}", in -> "x"));
        var reply = handler().handle(request("tools/call", "{\"name\":\"find_orders\",\"arguments\":[1]}"), caller);
        assertThat(reply.body()).contains("\"code\":-32602");
    }

    @Test
    void notificationsAreAcceptedWithoutABody() {
        var reply = handler().handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", caller);
        assertThat(reply.httpStatus()).isEqualTo(202);
        assertThat(reply.body()).isNull();
        assertThat(recorded).singleElement().extracting(McpRequestRecorder.McpCall::method)
                .isEqualTo("notifications/initialized");
    }

    @Test
    void malformedInputIsRejectedWithTheMatchingJsonRpcCode() {
        assertThat(handler().handle("{not json", caller).body()).contains("\"code\":-32700");
        var batch = handler().handle("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}]", caller);
        assertThat(batch.httpStatus()).isEqualTo(400);
        assertThat(batch.body()).contains("\"code\":-32600");
        assertThat(handler().handle("{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"ping\"}", caller).httpStatus())
                .isEqualTo(400);
    }

    @Test
    void unsupportedMethodsAreNotFound() {
        var reply = handler().handle(request("resources/list", null), caller);
        assertThat(reply.body()).contains("\"code\":-32601");
        assertThat(recorded).singleElement().extracting(McpRequestRecorder.McpCall::method)
                .isEqualTo("resources/list");
    }

    @Test
    void aFailingRecorderNeverChangesTheReply() {
        var handler = new McpProtocolHandler((c, s) -> List.of(), call -> {
            throw new IllegalStateException("store down");
        }, Clock.systemUTC(), "s", "1");
        assertThat(handler.handle(request("ping", null), caller).httpStatus()).isEqualTo(200);
    }
}
