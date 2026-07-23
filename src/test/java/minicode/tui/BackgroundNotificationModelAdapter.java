package minicode.tui;

import minicode.core.loop.ModelAdapter;
import minicode.core.message.ChatMessage;
import minicode.core.message.SystemMessage;
import minicode.core.message.UserMessage;
import minicode.core.step.AgentStep;
import minicode.core.step.AssistantKind;
import minicode.core.step.AssistantStep;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

/** 测试父 Agent 自动消费后台任务通知时使用的具名模型桩。 */
final class BackgroundNotificationModelAdapter implements ModelAdapter {
    private static final String CHILD_PROMPT_MARKER = "CodeAgent child agent with the built-in role";
    private static final String NOTIFICATION_MARKER = "<task-notification>";

    private final CountDownLatch summarized;
    private final String childOutput;
    private final String summaryOutput;
    private final String fallbackOutput;

    BackgroundNotificationModelAdapter(CountDownLatch summarized,
                                       String childOutput,
                                       String summaryOutput,
                                       String fallbackOutput) {
        this.summarized = Objects.requireNonNull(summarized, "summarized");
        this.childOutput = Objects.requireNonNull(childOutput, "childOutput");
        this.summaryOutput = Objects.requireNonNull(summaryOutput, "summaryOutput");
        this.fallbackOutput = Objects.requireNonNull(fallbackOutput, "fallbackOutput");
    }

    @Override
    public AgentStep next(List<ChatMessage> messages) {
        if (isChildAgentRequest(messages)) {
            return new AssistantStep(childOutput, AssistantKind.FINAL);
        }
        if (containsTaskNotification(messages)) {
            summarized.countDown();
            return new AssistantStep(summaryOutput, AssistantKind.FINAL);
        }
        return new AssistantStep(fallbackOutput, AssistantKind.FINAL);
    }

    static boolean isChildAgentRequest(List<ChatMessage> messages) {
        return !messages.isEmpty()
                && messages.getFirst() instanceof SystemMessage systemMessage
                && systemMessage.content().contains(CHILD_PROMPT_MARKER);
    }

    static boolean containsTaskNotification(List<ChatMessage> messages) {
        for (ChatMessage message : messages) {
            if (message instanceof UserMessage userMessage
                    && userMessage.content().contains(NOTIFICATION_MARKER)) {
                return true;
            }
        }
        return false;
    }
}
