package minicode.tools.extension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.core.turn.CancellationToken;
import minicode.core.turn.CancellationSource;
import minicode.core.event.AgentEventSink;
import minicode.core.loop.AgentLoop;
import minicode.core.message.AssistantMessage;
import minicode.core.message.ToolResultMessage;
import minicode.core.message.UserMessage;
import minicode.core.turn.AgentTurnRequest;
import minicode.core.turn.AgentTurnResult;
import minicode.core.turn.AgentTurnStopReason;
import minicode.integrations.feishu.calendar.CalendarTimeResolver;
import minicode.integrations.feishu.calendar.FeishuCalendarCreateRequest;
import minicode.integrations.feishu.calendar.FeishuCalendarCreateResult;
import minicode.integrations.feishu.calendar.FeishuCalendarGateway;
import minicode.integrations.feishu.calendar.FeishuCalendarGatewayException;
import minicode.permissions.api.PermissionPromptHandler;
import minicode.permissions.model.PermissionDecision;
import minicode.permissions.model.PermissionRequest;
import minicode.permissions.service.PromptingPermissionService;
import minicode.model.MockModelAdapter;
import minicode.tools.api.ToolCall;
import minicode.tools.api.ToolContext;
import minicode.tools.api.ValidationResult;
import minicode.tools.metadata.ToolCapability;
import minicode.tools.metadata.ToolOrigin;
import minicode.tools.result.ToolResult;
import minicode.tools.registry.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CreateFeishuCalendarEventToolTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-24T02:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    @Test
    void metadataExposesOnlyTheStronglyTypedExternalWriteTool() {
        CreateFeishuCalendarEventTool tool = tool(
                PermissionPromptHandler.allow(PermissionDecision.ALLOW_ONCE),
                new RecordingGateway()
        );

        assertEquals("create_feishu_calendar_event", tool.metadata().name());
        assertTrue(tool.metadata().description().contains("only for the user's explicit request"));
        assertTrue(tool.metadata().description().contains("direct clarification answer"));
        assertEquals(ToolOrigin.EXTENSION, tool.metadata().origin());
        assertEquals(java.util.Set.of(ToolCapability.EXTERNAL_WRITE), tool.metadata().capabilities());
        assertFalse(tool.inputSchema().path("additionalProperties").asBoolean(true));
        assertEquals(List.of("summary", "originalTimeText", "date", "startTime"),
                java.util.stream.StreamSupport.stream(
                                tool.inputSchema().path("required").spliterator(), false)
                        .map(JsonNode::asText)
                        .toList());
        assertFalse(tool.inputSchema().path("properties").has("calendarId"));
        assertFalse(tool.inputSchema().path("properties").has("timezone"));
        assertFalse(tool.inputSchema().path("properties").has("identity"));
    }

    @Test
    void validationNormalizesDefaultsAndRejectsConflictingOrUnstructuredFields() {
        CreateFeishuCalendarEventTool tool = tool(
                PermissionPromptHandler.allow(PermissionDecision.ALLOW_ONCE),
                new RecordingGateway()
        );

        ValidationResult valid = tool.validateInput(relativeInput("明天九点", 1, 9, "UNSPECIFIED"));
        ObjectNode conflicting = relativeInput("明天九点到十点", 1, 9, "UNSPECIFIED");
        conflicting.set("endTime", time(10, 0, "UNSPECIFIED"));
        conflicting.put("durationMinutes", 60);
        ObjectNode unknown = relativeInput("明天九点", 1, 9, "UNSPECIFIED");
        unknown.put("calendarId", "model-controlled");
        ObjectNode inconsistent = relativeInput("明天下午三点", 1, 15, "AFTERNOON");

        assertTrue(valid.valid(), valid.errors().toString());
        JsonNode normalized = valid.normalizedInput().orElseThrow();
        assertEquals(0, normalized.path("startTime").path("minute").asInt());
        assertEquals(5, normalized.path("reminderMinutes").asInt());
        assertFalse(tool.validateInput(conflicting).valid());
        assertTrue(tool.validateInput(unknown).errors().stream()
                .anyMatch(error -> error.contains("calendarId is not supported")));
        assertTrue(tool.validateInput(inconsistent).errors().stream()
                .anyMatch(error -> error.contains("inconsistent hour and dayPeriod")));
    }

    @Test
    void approvedCreateResolvesTimeShowsExactPreviewAndCallsGateway() {
        List<PermissionRequest> permissionRequests = new ArrayList<>();
        PermissionPromptHandler promptHandler = request -> {
            permissionRequests.add(request);
            return minicode.permissions.model.PermissionPromptResult.allow(
                    "allow_once", PermissionDecision.ALLOW_ONCE);
        };
        RecordingGateway gateway = new RecordingGateway();
        CreateFeishuCalendarEventTool tool = tool(promptHandler, gateway);
        ValidationResult validation = tool.validateInput(
                relativeInput("后天晚上八点", 2, 8, "EVENING"));

        ToolResult result = tool.run(validation.normalizedInput().orElseThrow(), context("turn-1"));

        assertFalse(result.error(), result.content());
        assertEquals(1, gateway.requests.size());
        FeishuCalendarCreateRequest request = gateway.requests.getFirst();
        assertEquals("2026-07-26T20:00+08:00[Asia/Shanghai]", request.start().toString());
        assertEquals("2026-07-26T20:30+08:00[Asia/Shanghai]", request.end().toString());
        assertEquals(5, request.reminderMinutes());
        assertEquals(1, permissionRequests.size());
        List<String> facts = permissionRequests.getFirst().details().facts();
        assertTrue(facts.contains("Service: Feishu Calendar"));
        assertTrue(facts.contains("Action: Create event"));
        assertTrue(facts.contains("Target: Personal primary calendar"));
        assertTrue(facts.contains("Original expression: 后天晚上八点"));
        assertTrue(facts.stream().anyMatch(value -> value.contains("Start: 2026-07-26 20:00")));
        assertTrue(result.content().contains("\"eventId\":\"event-123\""));
        assertTrue(result.content().contains("\"appLink\":\"https://applink.example/event-123\""));
        assertFalse(result.content().contains("calendar-id"));
    }

    @Test
    void deniedCreateNeverCallsGateway() {
        RecordingGateway gateway = new RecordingGateway();
        CreateFeishuCalendarEventTool tool = tool(
                PermissionPromptHandler.deny(PermissionDecision.DENY_WITH_FEEDBACK, "Not this event"),
                gateway
        );
        ValidationResult validation = tool.validateInput(
                relativeInput("明天九点", 1, 9, "UNSPECIFIED"));

        ToolResult result = tool.run(validation.normalizedInput().orElseThrow(), context("turn-1"));

        assertTrue(result.error());
        assertTrue(result.content().contains("Not this event"));
        assertEquals(0, gateway.requests.size());
    }

    @Test
    void retriesAcrossTurnsReuseIdempotencyKeyButDifferentEventsDoNot() {
        RecordingGateway gateway = new RecordingGateway();
        CreateFeishuCalendarEventTool tool = tool(
                PermissionPromptHandler.allow(PermissionDecision.ALLOW_ONCE),
                gateway
        );
        JsonNode normalized = tool.validateInput(
                relativeInput("明天九点", 1, 9, "UNSPECIFIED"))
                .normalizedInput().orElseThrow();

        tool.run(normalized, context("turn-1"));
        tool.run(normalized, context("turn-1"));
        tool.run(normalized, context("turn-2"));
        ObjectNode differentEvent = relativeInput("后天九点", 2, 9, "UNSPECIFIED");
        tool.run(tool.validateInput(differentEvent).normalizedInput().orElseThrow(), context("turn-3"));

        assertEquals(4, gateway.idempotencyKeys.size());
        assertEquals(gateway.idempotencyKeys.get(0), gateway.idempotencyKeys.get(1));
        assertEquals(gateway.idempotencyKeys.get(1), gateway.idempotencyKeys.get(2));
        assertNotEquals(gateway.idempotencyKeys.get(2), gateway.idempotencyKeys.get(3));
    }

    @Test
    void permissionPreviewEscapesTerminalControlAndFormattingCharacters() {
        List<PermissionRequest> permissionRequests = new ArrayList<>();
        PermissionPromptHandler promptHandler = request -> {
            permissionRequests.add(request);
            return minicode.permissions.model.PermissionPromptResult.allow(
                    "allow_once", PermissionDecision.ALLOW_ONCE);
        };
        CreateFeishuCalendarEventTool tool = tool(promptHandler, new RecordingGateway());
        ObjectNode input = relativeInput("明天\n九点\u001B[2J\u202E", 1, 9, "UNSPECIFIED");
        input.put("summary", "看\n八股文\u001B[2J");
        input.put("description", "第一行\n第二行\u202E");

        ToolResult result = tool.run(
                tool.validateInput(input).normalizedInput().orElseThrow(),
                context("turn-control")
        );

        assertFalse(result.error(), result.content());
        String preview = String.join("\n", permissionRequests.getFirst().details().facts());
        assertFalse(preview.contains("\u001B"));
        assertFalse(preview.contains("\u202E"));
        assertTrue(preview.contains("\\u{1B}"));
        assertTrue(preview.contains("\\u{202E}"));
        assertTrue(preview.contains("明天 九点"));
        assertTrue(preview.contains("Description (8 chars): 第一行 第二行"));
    }

    @Test
    void cancellationRacingAfterRemoteSuccessDoesNotDiscardCreatedEvent() {
        CancellationToken token = CancellationToken.create();
        FeishuCalendarGateway gateway = (request, idempotencyKey, cancellationToken) -> {
            token.requestCancellation(CancellationSource.USER, "arrived after remote success");
            return new FeishuCalendarCreateResult("event-after-cancel", Optional.empty());
        };
        CreateFeishuCalendarEventTool tool = tool(
                PermissionPromptHandler.allow(PermissionDecision.ALLOW_ONCE),
                gateway
        );
        JsonNode normalized = tool.validateInput(
                relativeInput("明天九点", 1, 9, "UNSPECIFIED"))
                .normalizedInput().orElseThrow();
        ToolContext context = new ToolContext(
                tempDir,
                "session-1",
                Optional.of("turn-race"),
                Optional.of("tool-use-race"),
                token
        );

        ToolResult result = tool.run(normalized, context);

        assertFalse(result.error(), result.content());
        assertTrue(result.content().contains("event-after-cancel"));
    }

    @Test
    void resolverAndGatewayFailuresAreReturnedWithoutWritingSuccessClaims() {
        RecordingGateway gateway = new RecordingGateway();
        gateway.failure = new FeishuCalendarGatewayException("lark-cli authentication is required");
        CreateFeishuCalendarEventTool tool = tool(
                PermissionPromptHandler.allow(PermissionDecision.ALLOW_ONCE),
                gateway
        );
        ObjectNode past = relativeInput("今天九点", 0, 9, "UNSPECIFIED");

        ToolResult pastResult = tool.run(
                tool.validateInput(past).normalizedInput().orElseThrow(),
                context("turn-1")
        );
        ToolResult gatewayResult = tool.run(
                tool.validateInput(relativeInput("明天九点", 1, 9, "UNSPECIFIED"))
                        .normalizedInput().orElseThrow(),
                context("turn-2")
        );

        assertTrue(pastResult.error());
        assertTrue(pastResult.content().contains("strictly in the future"));
        assertTrue(gatewayResult.error());
        assertTrue(gatewayResult.content().contains("authentication is required"));
        assertFalse(gatewayResult.content().contains("\"status\":\"created\""));
    }

    @Test
    void agentLoopFeedsCalendarToolResultBackToTheModelBeforeFinalReply() {
        RecordingGateway gateway = new RecordingGateway();
        CreateFeishuCalendarEventTool calendarTool = tool(
                PermissionPromptHandler.allow(PermissionDecision.ALLOW_ONCE),
                gateway
        );
        ToolRegistry registry = new ToolRegistry();
        registry.register(calendarTool);
        ObjectNode input = relativeInput("明天九点", 1, 9, "UNSPECIFIED");
        AgentLoop loop = new AgentLoop(
                MockModelAdapter.toolThenFinal(
                        new ToolCall("calendar-tool-use", CreateFeishuCalendarEventTool.NAME, input),
                        "飞书日程已创建"
                ),
                AgentEventSink.noOp(),
                registry
        );

        AgentTurnResult result = loop.runTurn(new AgentTurnRequest(
                "turn-loop",
                tempDir,
                "session-loop",
                List.of(new UserMessage("帮我创建日程：明天九点看八股文")),
                3,
                Optional.empty()
        ));

        assertEquals(AgentTurnStopReason.FINAL, result.stopReason());
        ToolResultMessage toolResult = result.messages().stream()
                .filter(ToolResultMessage.class::isInstance)
                .map(ToolResultMessage.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(CreateFeishuCalendarEventTool.NAME, toolResult.toolName());
        assertTrue(toolResult.content().contains("\"status\":\"created\""));
        assertEquals("飞书日程已创建",
                ((AssistantMessage) result.messages().getLast()).content());
        assertEquals(1, gateway.requests.size());
    }

    private CreateFeishuCalendarEventTool tool(PermissionPromptHandler promptHandler,
                                               FeishuCalendarGateway gateway) {
        return new CreateFeishuCalendarEventTool(
                new PromptingPermissionService(promptHandler),
                new CalendarTimeResolver(CLOCK, SHANGHAI, 30),
                gateway,
                5
        );
    }

    private ToolContext context(String turnId) {
        return new ToolContext(
                tempDir,
                "session-1",
                Optional.of(turnId),
                Optional.of("tool-use-1"),
                CancellationToken.none()
        );
    }

    private static ObjectNode relativeInput(String originalTimeText,
                                            int offsetDays,
                                            int hour,
                                            String dayPeriod) {
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("summary", "看八股文");
        input.put("originalTimeText", originalTimeText);
        input.putObject("date")
                .put("kind", "RELATIVE_DAY")
                .put("offsetDays", offsetDays);
        input.set("startTime", time(hour, 0, dayPeriod));
        return input;
    }

    private static ObjectNode time(int hour, int minute, String dayPeriod) {
        return JsonNodeFactory.instance.objectNode()
                .put("hour", hour)
                .put("minute", minute)
                .put("dayPeriod", dayPeriod);
    }

    private static final class RecordingGateway implements FeishuCalendarGateway {
        private final List<FeishuCalendarCreateRequest> requests = new ArrayList<>();
        private final List<String> idempotencyKeys = new ArrayList<>();
        private RuntimeException failure;

        @Override
        public FeishuCalendarCreateResult create(FeishuCalendarCreateRequest request,
                                                 String idempotencyKey,
                                                 CancellationToken cancellationToken) {
            requests.add(request);
            idempotencyKeys.add(idempotencyKey);
            if (failure != null) {
                throw failure;
            }
            return new FeishuCalendarCreateResult(
                    "event-123",
                    Optional.of("https://applink.example/event-123")
            );
        }
    }
}
