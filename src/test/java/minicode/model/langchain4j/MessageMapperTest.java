package minicode.model.langchain4j;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import minicode.core.message.AssistantMessage;
import minicode.core.message.AssistantProgressMessage;
import minicode.core.message.AssistantThinkingMessage;
import minicode.core.message.AssistantToolCallMessage;
import minicode.core.message.ContextSummaryMessage;
import minicode.core.message.ToolResultMessage;
import minicode.core.message.UserMessage;
import minicode.model.ProviderThinkingBlock;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class MessageMapperTest {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    @Test
    void mergesSystemsAndConsecutiveAssistantRecordsIntoProviderTurns() {
        ObjectNode thinking = JSON.objectNode()
                .put("type", "thinking")
                .put("thinking", "inspect the repository")
                .put("signature", "signature-1");
        ObjectNode redacted = JSON.objectNode()
                .put("type", "redacted_thinking")
                .put("data", "opaque-thinking");

        List<dev.langchain4j.data.message.ChatMessage> mapped = new MessageMapper().map(List.of(
                new minicode.core.message.SystemMessage("system one"),
                new UserMessage("start"),
                new minicode.core.message.SystemMessage("system two"),
                new AssistantThinkingMessage(List.of(
                        new ProviderThinkingBlock("thinking", thinking),
                        new ProviderThinkingBlock("redacted_thinking", redacted)
                )),
                new AssistantMessage("working"),
                new AssistantProgressMessage("reading files"),
                new AssistantToolCallMessage("tool-1", "read_file",
                        JSON.objectNode().put("path", "README.md")),
                new AssistantToolCallMessage("tool-2", "grep_files",
                        JSON.objectNode().put("pattern", "AgentLoop")),
                new ToolResultMessage("tool-1", "read_file", "contents", true),
                new ContextSummaryMessage("earlier work", 4, Instant.EPOCH),
                new AssistantMessage("done")
        ));

        assertEquals(6, mapped.size());
        assertEquals("system one\n\nsystem two",
                assertInstanceOf(dev.langchain4j.data.message.SystemMessage.class, mapped.get(0)).text());
        assertEquals("start",
                assertInstanceOf(dev.langchain4j.data.message.UserMessage.class, mapped.get(1)).singleText());

        AiMessage assistant = assertInstanceOf(AiMessage.class, mapped.get(2));
        assertEquals("working\n<progress>\nreading files\n</progress>", assistant.text());
        assertEquals("inspect the repository", assistant.thinking());
        assertEquals("signature-1",
                assistant.attribute(MessageMapper.THINKING_SIGNATURE_KEY, String.class));
        assertEquals(List.of("opaque-thinking"),
                assistant.attribute(MessageMapper.REDACTED_THINKING_KEY, List.class));
        assertEquals(List.of("tool-1", "tool-2"),
                assistant.toolExecutionRequests().stream().map(ToolExecutionRequest::id).toList());
        assertEquals("{\"path\":\"README.md\"}",
                assistant.toolExecutionRequests().getFirst().arguments());

        ToolExecutionResultMessage result =
                assertInstanceOf(ToolExecutionResultMessage.class, mapped.get(3));
        assertEquals("tool-1", result.id());
        assertEquals("read_file", result.toolName());
        assertEquals("contents", result.text());
        assertEquals(Boolean.TRUE, result.isError());

        assertEquals("[Context Summary from earlier conversation]\nearlier work",
                assertInstanceOf(dev.langchain4j.data.message.UserMessage.class, mapped.get(4)).singleText());
        assertEquals("done", assertInstanceOf(AiMessage.class, mapped.get(5)).text());
    }

    @Test
    void toolResultsSplitAssistantRunsAndPreserveFalseErrorFlag() {
        List<dev.langchain4j.data.message.ChatMessage> mapped = new MessageMapper().map(List.of(
                new AssistantMessage("before"),
                new ToolResultMessage("tool-1", "read_file", "", false),
                new AssistantMessage("after")
        ));

        assertEquals(3, mapped.size());
        assertEquals("before", assertInstanceOf(AiMessage.class, mapped.get(0)).text());
        ToolExecutionResultMessage result =
                assertInstanceOf(ToolExecutionResultMessage.class, mapped.get(1));
        assertEquals("", result.text());
        assertEquals(Boolean.FALSE, result.isError());
        assertEquals("after", assertInstanceOf(AiMessage.class, mapped.get(2)).text());
    }

    @Test
    void redactedThinkingOnlyRunStillProducesOneAiMessage() {
        ObjectNode redacted = JSON.objectNode()
                .put("type", "redacted_thinking")
                .put("data", "opaque-only");

        List<dev.langchain4j.data.message.ChatMessage> mapped = new MessageMapper().map(List.of(
                new AssistantThinkingMessage(List.of(
                        new ProviderThinkingBlock("redacted_thinking", redacted)
                ))
        ));

        assertEquals(1, mapped.size());
        AiMessage assistant = assertInstanceOf(AiMessage.class, mapped.getFirst());
        assertNull(assistant.text());
        assertNull(assistant.thinking());
        assertEquals(List.of("opaque-only"),
                assistant.attribute(MessageMapper.REDACTED_THINKING_KEY, List.class));
    }

    @Test
    void emptyThinkingRecordDoesNotCreateAnEmptyProviderMessage() {
        List<dev.langchain4j.data.message.ChatMessage> mapped = new MessageMapper().map(List.of(
                new UserMessage("before"),
                new AssistantThinkingMessage(List.of()),
                new UserMessage("after")
        ));

        assertEquals(2, mapped.size());
        assertEquals("before",
                assertInstanceOf(dev.langchain4j.data.message.UserMessage.class, mapped.get(0))
                        .singleText());
        assertEquals("after",
                assertInstanceOf(dev.langchain4j.data.message.UserMessage.class, mapped.get(1))
                        .singleText());
    }

    @Test
    void midConversationSystemMessageKeepsAssistantTurnOrdinalsSeparate() {
        List<dev.langchain4j.data.message.ChatMessage> mapped = new MessageMapper().map(List.of(
                new AssistantMessage("before system"),
                new minicode.core.message.SystemMessage("system"),
                new AssistantMessage("after system")
        ));

        assertEquals(3, mapped.size());
        assertEquals("system",
                assertInstanceOf(dev.langchain4j.data.message.SystemMessage.class, mapped.get(0)).text());
        assertEquals("before system", assertInstanceOf(AiMessage.class, mapped.get(1)).text());
        assertEquals("after system", assertInstanceOf(AiMessage.class, mapped.get(2)).text());
    }
}
