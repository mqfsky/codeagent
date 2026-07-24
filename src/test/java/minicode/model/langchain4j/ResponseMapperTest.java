package minicode.model.langchain4j;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.model.anthropic.AnthropicChatResponseMetadata;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatResponseMetadata;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import minicode.config.ProviderKind;
import minicode.core.step.AssistantKind;
import minicode.core.step.AssistantStep;
import minicode.core.step.ContentKind;
import minicode.core.step.ToolCallsStep;
import minicode.model.ProviderRequestException;
import minicode.model.ProviderUsage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponseMapperTest {
    private final ResponseMapper mapper = new ResponseMapper();

    @Test
    void mapsOpenAiFinalThinkingLengthAndUsage() {
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.builder()
                        .text("<final>done</final>")
                        .thinking("reason carefully")
                        .build())
                .metadata(OpenAiChatResponseMetadata.builder()
                        .finishReason(FinishReason.LENGTH)
                        .tokenUsage(new TokenUsage(11, 4, 15))
                        .build())
                .build();

        AssistantStep step = assertInstanceOf(
                AssistantStep.class,
                mapper.map(ProviderKind.OPENAI_COMPATIBLE, response)
        );

        assertEquals("done", step.content());
        assertEquals(AssistantKind.FINAL, step.kind());
        assertEquals(new ProviderUsage(11, 4, 15), step.usage().orElseThrow());
        assertEquals("max_tokens", step.diagnostics().orElseThrow().stopReason().orElseThrow());
        assertEquals(List.of("openai_message", "thinking"), step.diagnostics().orElseThrow().blockTypes());
        assertEquals("thinking", step.thinkingBlocks().getFirst().type());
        assertEquals("reason carefully", step.thinkingBlocks().getFirst().raw().path("thinking").asText());
    }

    @Test
    void mapsProgressContentAndMultipleToolCalls() {
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.builder()
                        .text("<progress>checking files</progress>")
                        .toolExecutionRequests(List.of(
                                toolRequest("call-1", "read_file", "{\"path\":\"README.md\"}"),
                                toolRequest("call-2", "list_files", "{\"path\":\"src\"}")
                        ))
                        .build())
                .finishReason(FinishReason.TOOL_EXECUTION)
                .build();

        ToolCallsStep step = assertInstanceOf(
                ToolCallsStep.class,
                mapper.map(ProviderKind.OPENAI_COMPATIBLE, response)
        );

        assertEquals("checking files", step.content().orElseThrow());
        assertEquals(ContentKind.PROGRESS, step.contentKind());
        assertEquals(List.of("call-1", "call-2"),
                step.calls().stream().map(call -> call.id()).toList());
        assertEquals("README.md", step.calls().getFirst().input().path("path").asText());
        assertEquals("tool_calls", step.diagnostics().orElseThrow().stopReason().orElseThrow());
    }

    @Test
    void mapsAnthropicRawDiagnosticsThinkingAndCacheInclusiveUsage() {
        String rawBody = """
                {
                  "stop_reason": "pause_turn",
                  "content": [
                    {"type": "thinking", "thinking": "inspect", "signature": "sig"},
                    {"type": "redacted_thinking", "data": "opaque"},
                    {"type": "text", "text": "working"},
                    {"type": "tool_use", "id": "raw-id", "name": "raw-tool", "input": {}},
                    {"type": "future_block", "payload": "unknown"}
                  ]
                }
                """;
        AnthropicTokenUsage tokenUsage = AnthropicTokenUsage.builder()
                .inputTokenCount(10)
                .outputTokenCount(5)
                .cacheCreationInputTokens(3)
                .cacheReadInputTokens(2)
                .build();
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.builder()
                        .text("[PROGRESS] working")
                        .thinking("LangChain4j aggregate")
                        .toolExecutionRequests(List.of(
                                toolRequest("call-1", "read_file", "{\"path\":\"pom.xml\"}")
                        ))
                        .build())
                .metadata(AnthropicChatResponseMetadata.builder()
                        .finishReason(FinishReason.OTHER)
                        .tokenUsage(tokenUsage)
                        .rawHttpResponse(SuccessfulHttpResponse.builder()
                                .statusCode(200)
                                .body(rawBody)
                                .build())
                        .build())
                .build();

        ToolCallsStep step = assertInstanceOf(
                ToolCallsStep.class,
                mapper.map(ProviderKind.ANTHROPIC, response)
        );

        assertEquals("working", step.content().orElseThrow());
        assertEquals(ContentKind.PROGRESS, step.contentKind());
        assertEquals("call-1", step.calls().getFirst().id());
        assertEquals(new ProviderUsage(15, 5, 20), step.usage().orElseThrow());
        assertEquals("pause_turn", step.diagnostics().orElseThrow().stopReason().orElseThrow());
        assertEquals(List.of("thinking", "redacted_thinking", "text", "tool_use", "future_block"),
                step.diagnostics().orElseThrow().blockTypes());
        assertEquals(List.of("future_block"), step.diagnostics().orElseThrow().ignoredBlockTypes());
        assertEquals(List.of("thinking", "redacted_thinking"),
                step.thinkingBlocks().stream().map(block -> block.type()).toList());
        assertEquals("sig", step.thinkingBlocks().getFirst().raw().path("signature").asText());
        assertEquals("opaque", step.thinkingBlocks().getLast().raw().path("data").asText());
    }

    @Test
    void rejectsMalformedOrNonObjectToolArguments() {
        ChatResponse malformed = responseWithTool(toolRequest("call-1", "read_file", "{broken"));
        ChatResponse array = responseWithTool(toolRequest("call-1", "read_file", "[]"));

        ProviderRequestException malformedError = assertThrows(
                ProviderRequestException.class,
                () -> mapper.map(ProviderKind.OPENAI_COMPATIBLE, malformed)
        );
        ProviderRequestException arrayError = assertThrows(
                ProviderRequestException.class,
                () -> mapper.map(ProviderKind.OPENAI_COMPATIBLE, array)
        );

        assertTrue(malformedError.getMessage().contains("not valid JSON"));
        assertTrue(arrayError.getMessage().contains("must be a JSON object"));
    }

    @Test
    void rejectsBlankFieldsAndDuplicateToolCallIds() {
        ChatResponse blankId = responseWithTool(toolRequest(" ", "read_file", "{}"));
        ChatResponse blankName = responseWithTool(toolRequest("call-1", " ", "{}"));
        ChatResponse duplicateId = ChatResponse.builder()
                .aiMessage(AiMessage.from(List.of(
                        toolRequest("call-1", "read_file", "{}"),
                        toolRequest("call-1", "list_files", "{}")
                )))
                .build();

        assertThrows(ProviderRequestException.class,
                () -> mapper.map(ProviderKind.OPENAI_COMPATIBLE, blankId));
        assertThrows(ProviderRequestException.class,
                () -> mapper.map(ProviderKind.OPENAI_COMPATIBLE, blankName));
        ProviderRequestException duplicateError = assertThrows(
                ProviderRequestException.class,
                () -> mapper.map(ProviderKind.OPENAI_COMPATIBLE, duplicateId)
        );
        assertTrue(duplicateError.getMessage().contains("Duplicate tool call id"));
    }

    private static ChatResponse responseWithTool(ToolExecutionRequest request) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(request))
                .build();
    }

    private static ToolExecutionRequest toolRequest(String id, String name, String arguments) {
        return ToolExecutionRequest.builder()
                .id(id)
                .name(name)
                .arguments(arguments)
                .build();
    }
}
