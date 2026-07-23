package minicode.agent.runtime;

import minicode.agent.model.AgentRunResult;
import minicode.core.message.AssistantMessage;
import minicode.core.message.AssistantProgressMessage;
import minicode.core.message.ChatMessage;
import minicode.core.message.UserMessage;
import minicode.core.turn.AgentTurnResult;
import minicode.core.turn.CancellationDetails;
import minicode.core.turn.CancellationPhase;
import minicode.core.turn.CancellationSource;
import minicode.core.turn.EmptyFallbackDetails;
import minicode.core.turn.ModelErrorDetails;
import minicode.core.turn.TurnCancellation;
import minicode.core.turn.TurnError;
import minicode.core.turn.TurnErrorSource;
import minicode.session.plan.TurnPersistencePlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRunResultMapperTest {
    private final AgentRunResultMapper mapper = new AgentRunResultMapper();

    @Test
    void mapsFinalAsSuccessAndMaxStepsAsFailure() {
        List<ChatMessage> messages = List.of(
                new AssistantProgressMessage("progress"), new AssistantMessage("final"));

        AgentRunResult finished = mapper.map(AgentTurnResult.finalResult(messages, TurnPersistencePlan.empty()));
        AgentRunResult maxSteps = mapper.map(AgentTurnResult.maxSteps(messages, TurnPersistencePlan.empty()));

        assertEquals("final", finished.output());
        assertEquals("FINAL", finished.stopReason());
        assertTrue(finished.successful());
        assertEquals("MAX_STEPS", maxSteps.stopReason());
        assertFalse(maxSteps.successful());
        assertEquals("(agent produced no output)", maxSteps.output());
        assertEquals(Optional.of("Child agent reached maximum steps"), maxSteps.error());
    }

    @Test
    void finalWithoutAssistantMessageIsFailure() {
        AgentRunResult result = mapper.map(AgentTurnResult.finalResult(
                List.of(new AssistantProgressMessage("still working")),
                TurnPersistencePlan.empty()
        ));

        assertEquals("(agent produced no output)", result.output());
        assertFalse(result.successful());
        assertTrue(result.error().orElseThrow().contains("without"));
    }

    @Test
    void finalDoesNotReuseHistoricalAssistantText() {
        AgentRunResult result = mapper.map(AgentTurnResult.finalResult(
                List.of(new AssistantMessage("I will call a tool"), new UserMessage("tool result")),
                TurnPersistencePlan.empty()
        ));

        assertEquals("(agent produced no output)", result.output());
        assertFalse(result.successful());
    }

    @Test
    void mapsModelErrorCancellationAwaitUserAndEmptyFallback() {
        AgentRunResult modelError = mapper.map(AgentTurnResult.modelError(List.of(), TurnPersistencePlan.empty(),
                new ModelErrorDetails(new TurnError("provider failed", TurnErrorSource.MODEL, true,
                        Optional.empty(), Optional.empty()))));
        AgentRunResult cancelled = mapper.map(AgentTurnResult.cancelled(List.of(), TurnPersistencePlan.empty(),
                new CancellationDetails(new TurnCancellation(
                        CancellationSource.USER, CancellationPhase.MODEL_REQUEST, "stop"))));
        AgentRunResult awaitUser = mapper.map(AgentTurnResult.awaitUser(List.of(), TurnPersistencePlan.empty()));
        AgentRunResult empty = mapper.map(AgentTurnResult.emptyFallback(List.of(), TurnPersistencePlan.empty(),
                Optional.of(new EmptyFallbackDetails(Optional.of("empty response"), Optional.empty(), false, 0))));

        assertEquals("(agent produced no output)", modelError.output());
        assertEquals(Optional.of("provider failed"), modelError.error());
        assertTrue(cancelled.cancelled());
        assertEquals(Optional.of("stop"), cancelled.error());
        assertFalse(awaitUser.successful());
        assertTrue(awaitUser.error().orElseThrow().contains("cannot wait"));
        assertFalse(empty.successful());
        assertEquals(Optional.of("empty response"), empty.error());
    }
}
