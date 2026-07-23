package minicode.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import minicode.agent.model.AgentRunMode;
import minicode.agent.model.AgentType;
import minicode.config.ProviderKind;
import minicode.config.RuntimeConfig;
import minicode.core.message.UserMessage;
import minicode.model.anthropic.AnthropicModelAdapter;
import minicode.model.anthropic.AnthropicTransport;
import minicode.model.openai.OpenAIModelAdapter;
import minicode.tools.api.Tool;
import minicode.tools.api.ToolCall;
import minicode.tools.api.ToolContext;
import minicode.tools.api.ValidationResult;
import minicode.tools.metadata.ToolCapability;
import minicode.tools.metadata.ToolMetadata;
import minicode.tools.metadata.ToolOrigin;
import minicode.tools.metadata.ToolStatus;
import minicode.tools.registry.ToolRegistry;
import minicode.tools.result.ToolResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderToolIsolationTest {
    @Test
    void openAiAndAnthropicSchemasUseTheSameFilteredRegistryAsExecution() throws Exception {
        ToolRegistry parent = new ToolRegistry();
        parent.register(tool("read_file", ToolOrigin.BUILTIN, ToolCapability.READ));
        parent.register(tool("write_file", ToolOrigin.BUILTIN, ToolCapability.WRITE));
        parent.register(tool("run_command", ToolOrigin.BUILTIN, ToolCapability.COMMAND));
        parent.register(tool("ask_user", ToolOrigin.BUILTIN, ToolCapability.ASK_USER));
        parent.register(tool("agent", ToolOrigin.BUILTIN, ToolCapability.BACKGROUND_TASK));
        parent.register(tool("mcp__server__tool", ToolOrigin.MCP, ToolCapability.COMMAND));
        ToolRegistry child = new ChildToolRegistryFactory().create(
                parent, AgentSpec.forType(AgentType.EXPLORE), AgentRunMode.BACKGROUND);

        OpenAIModelAdapter openAi = (OpenAIModelAdapter) new OpenAIModelAdapter(
                config(ProviderKind.OPENAI_COMPATIBLE), parent).fork(child);
        Method buildTools = OpenAIModelAdapter.class.getDeclaredMethod("buildTools");
        buildTools.setAccessible(true);
        JsonNode openAiTools = (JsonNode) buildTools.invoke(openAi);
        assertEquals(List.of("read_file", "run_command", "mcp__server__tool"), StreamSupport.stream(openAiTools.spliterator(), false)
                .map(node -> node.at("/function/name").asText()).toList());

        AtomicReference<JsonNode> anthropicBody = new AtomicReference<>();
        AnthropicTransport transport = (url, headers, requestBody) -> {
            anthropicBody.set(requestBody);
            return new AnthropicTransport.Response(200, Map.of(),
                    "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"done\"}]}");
        };
        AnthropicModelAdapter anthropic = (AnthropicModelAdapter) new AnthropicModelAdapter(
                config(ProviderKind.ANTHROPIC), parent, transport).fork(child);
        anthropic.next(List.of(new UserMessage("inspect")));
        assertEquals(List.of("read_file", "run_command", "mcp__server__tool"), StreamSupport.stream(
                        anthropicBody.get().get("tools").spliterator(), false)
                .map(node -> node.get("name").asText()).toList());

        ToolResult blockedExecution = child.execute(
                new ToolCall("write", "write_file", JsonNodeFactory.instance.objectNode()),
                new ToolContext(Path.of("."), "session", Optional.of("turn"), Optional.of("write")));
        assertTrue(blockedExecution.error());
        assertEquals("Unknown tool: write_file", blockedExecution.content());
    }

    private static RuntimeConfig config(ProviderKind provider) {
        return new RuntimeConfig(provider, "test-model", "https://provider.example", Optional.of("key"),
                Optional.empty(), Optional.of(4_096), Optional.of(128_000), "test");
    }

    private static Tool tool(String name, ToolOrigin origin, ToolCapability capability) {
        JsonNode schema = JsonNodeFactory.instance.objectNode().put("type", "object");
        ToolMetadata metadata = new ToolMetadata(name, "test", schema, origin, Set.of(capability),
                ToolStatus.AVAILABLE);
        return new Tool() {
            @Override
            public ToolMetadata metadata() {
                return metadata;
            }

            @Override
            public JsonNode inputSchema() {
                return schema;
            }

            @Override
            public ValidationResult validateInput(JsonNode input) {
                return ValidationResult.valid(input);
            }

            @Override
            public ToolResult run(JsonNode input, ToolContext context) {
                return ToolResult.ok("ok");
            }
        };
    }
}
