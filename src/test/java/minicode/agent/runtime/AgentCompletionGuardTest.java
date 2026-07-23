package minicode.agent.runtime;

import minicode.core.step.AssistantKind;
import minicode.core.step.AssistantStep;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCompletionGuardTest {
    @Test
    void acceptsOrdinaryUnspecifiedAnswer() {
        assertTrue(AgentCompletionGuard.INSTANCE.rejectionReason(
                new AssistantStep("A useful final answer.", AssistantKind.UNSPECIFIED)
        ).isEmpty());
    }

    @Test
    void rejectsProtocolMarkupReturnedAsPlainText() {
        String content = "Still working.\n</parameter>\n</｜DSML｜invoke>\n</｜DSML｜tool_calls>";
        assertTrue(AgentCompletionGuard.INSTANCE.rejectionReason(
                new AssistantStep(content, AssistantKind.UNSPECIFIED)
        ).orElseThrow().contains("tool call"));
    }

    @Test
    void acceptsExplicitFinalThatDocumentsToolCallMarkup() {
        AssistantStep step = new AssistantStep(
                "A provider may encode this as `<tool_call>{...}</tool_call>`.",
                AssistantKind.FINAL
        );

        assertTrue(AgentCompletionGuard.INSTANCE.rejectionReason(step).isEmpty());
    }

    @Test
    void acceptsUnspecifiedReportThatDocumentsGenericToolCallMarkup() {
        AssistantStep step = new AssistantStep(
                "A generic example is:\n```xml\n<tool_call>{...}</tool_call>\n```",
                AssistantKind.UNSPECIFIED
        );

        assertTrue(AgentCompletionGuard.INSTANCE.rejectionReason(step).isEmpty());
    }

    @Test
    void acceptsUnspecifiedReportThatDocumentsDsmlInsideCodeFence() {
        AssistantStep step = new AssistantStep(
                "The leaked form looks like:\n```text\n</parameter>\n</｜DSML｜invoke>\n```",
                AssistantKind.UNSPECIFIED
        );

        assertTrue(AgentCompletionGuard.INSTANCE.rejectionReason(step).isEmpty());
    }

    @Test
    void rejectsLongRepetitivePlanning() {
        String content = "Let me inspect one more thing before finishing.\n".repeat(300);
        assertTrue(AgentCompletionGuard.INSTANCE.rejectionReason(
                new AssistantStep(content, AssistantKind.FINAL)
        ).orElseThrow().contains("repetitive"));
    }
}
