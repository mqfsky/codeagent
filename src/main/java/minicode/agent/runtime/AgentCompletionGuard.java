package minicode.agent.runtime;

import minicode.core.loop.AssistantCompletionGuard;
import minicode.core.step.AssistantKind;
import minicode.core.step.AssistantStep;

import java.util.Locale;
import java.util.Optional;

/** Rejects malformed or visibly non-convergent completion text from a sub-agent. */
final class AgentCompletionGuard implements AssistantCompletionGuard {
    static final AgentCompletionGuard INSTANCE = new AgentCompletionGuard();

    private static final int REPETITION_MIN_CHARS = 8_000;
    private static final int REPETITION_SIGNAL_LIMIT = 24;
    private static final int PROTOCOL_TAIL_CHARS = 1_024;

    private AgentCompletionGuard() {
    }

    @Override
    public Optional<String> rejectionReason(AssistantStep step) {
        String content = step.content().strip();
        // An explicit final answer may legitimately document tool-call markup in a report or code block.
        // This heuristic is only for unclassified provider text, which is where leaked tool calls land.
        if (step.kind() == AssistantKind.UNSPECIFIED && hasLeakedDsmlInvocationTail(content)) {
            return Optional.of("protocol-shaped tool call text was returned instead of a real tool call");
        }
        if (isRepetitivePlanning(content)) {
            return Optional.of("repetitive planning text was returned instead of a final answer");
        }
        return Optional.empty();
    }

    private static boolean hasLeakedDsmlInvocationTail(String content) {
        String normalized = textOutsideCodeFences(content.toLowerCase(Locale.ROOT));
        String tail = normalized.substring(Math.max(0, normalized.length() - PROTOCOL_TAIL_CHARS)).stripTrailing();
        return tail.contains("dsml")
                && tail.contains("</parameter>")
                && tail.contains("invoke>")
                && (tail.endsWith("invoke>")
                    || tail.endsWith("tool_calls>")
                    || tail.endsWith("function_calls>"));
    }

    private static String textOutsideCodeFences(String content) {
        String[] segments = content.split("```", -1);
        StringBuilder outside = new StringBuilder(content.length());
        for (int index = 0; index < segments.length; index += 2) {
            outside.append(segments[index]);
        }
        return outside.toString();
    }

    private static boolean isRepetitivePlanning(String content) {
        if (content.length() < REPETITION_MIN_CHARS) {
            return false;
        }
        String normalized = content.toLowerCase(Locale.ROOT);
        int signals = countOccurrences(normalized, "let me ")
                + countOccurrences(normalized, "i should ")
                + countOccurrences(normalized, "i'll now ")
                + countOccurrences(content, "接下来我")
                + countOccurrences(content, "让我");
        return signals >= REPETITION_SIGNAL_LIMIT;
    }

    private static int countOccurrences(String value, String needle) {
        int count = 0;
        int fromIndex = 0;
        while ((fromIndex = value.indexOf(needle, fromIndex)) >= 0) {
            count++;
            fromIndex += needle.length();
        }
        return count;
    }
}
