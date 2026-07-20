package minicode.model.openai;

import com.fasterxml.jackson.databind.JsonNode;
import minicode.config.ProviderKind;
import minicode.config.RuntimeConfig;
import minicode.core.message.AgentNotificationMessage;
import minicode.tools.registry.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenAIModelAdapterTest {
    @Test
    void mapsAgentNotificationToUserMessage() throws Exception {
        OpenAIModelAdapter adapter = new OpenAIModelAdapter(config(), new ToolRegistry());
        Method buildMessages = OpenAIModelAdapter.class.getDeclaredMethod("buildMessages", List.class);
        buildMessages.setAccessible(true);

        JsonNode messages = (JsonNode) buildMessages.invoke(adapter, List.of(
                new AgentNotificationMessage("task-1", "COMPLETED", "found <two> & three files")));

        assertEquals("user", messages.get(0).get("role").asText());
        assertEquals("<task-notification task_id=\"task-1\" status=\"COMPLETED\">"
                        + "found &lt;two&gt; &amp; three files</task-notification>",
                messages.get(0).get("content").asText());
    }

    private static RuntimeConfig config() {
        return new RuntimeConfig(
                ProviderKind.OPENAI_COMPATIBLE,
                "openai-test",
                "https://openai.example",
                Optional.of("key"),
                Optional.empty(),
                Optional.of(4096),
                Optional.of(128_000),
                "test"
        );
    }
}
