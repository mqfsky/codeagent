package minicode.context.compact;

import minicode.context.accounting.TokenAccountingService;
import minicode.core.message.AssistantMessage;
import minicode.core.message.AssistantProgressMessage;
import minicode.core.message.AssistantThinkingMessage;
import minicode.core.message.AssistantToolCallMessage;
import minicode.core.message.ChatMessage;
import minicode.core.message.ChatMessages;
import minicode.core.message.ContextSummaryMessage;
import minicode.core.message.SystemMessage;
import minicode.core.message.ToolResultMessage;
import minicode.core.message.UserMessage;
import minicode.core.step.AgentStep;
import minicode.core.step.AssistantStep;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class CompactService {
    private static final int MIN_KEEP_MESSAGES = 8;
    private static final long MAX_KEEP_TOKENS = 8_000;
    private static final int TOOL_RESULT_SUMMARY_PREVIEW_CHARS = 500;
    private static final String MANUAL_STALE_REASON =
            "conversation was manually compacted after this provider usage was recorded";
    private static final String AUTO_STALE_REASON =
            "conversation was automatically compacted after this provider usage was recorded";

    private final Clock clock;
    private final TokenAccountingService accountingService;

    public CompactService() {
        this(Clock.systemUTC());
    }

    public CompactService(Clock clock) {
        this(clock, new TokenAccountingService());
    }

    CompactService(Clock clock, TokenAccountingService accountingService) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.accountingService = Objects.requireNonNull(accountingService, "accountingService");
    }

    public ManualCompactResult compact(CompactRequest request) {
        Objects.requireNonNull(request, "request");
        List<ChatMessage> messages = request.messages();
        // 判断消息够不够多，不够就跳过
        if (messages.size() <= MIN_KEEP_MESSAGES + 1) {
            return ManualCompactResult.skipped(messages, "not enough messages to compact");
        }

        // 找一个 retention boundary，决定哪些旧消息压缩、哪些近消息保留
        // boundary前的压缩，后的保留
        int boundary = findRetentionBoundary(messages);
        if (boundary <= 1 || boundary >= messages.size()) {
            return ManualCompactResult.skipped(messages, "no compactable messages");
        }

        // 需要压缩的消息列表
        List<ChatMessage> messagesToCompress = messages.subList(1, boundary);
        if (messagesToCompress.isEmpty()) {
            return ManualCompactResult.skipped(messages, "no compactable messages");
        }

        // 压缩前总 token 数量
        long tokensBefore = accountingService.account(messages).totalTokens();
        // 构造 summarize 请求消息
        List<ChatMessage> summaryRequestMessages = List.of(
                new SystemMessage("You summarize MiniCode coding-agent conversation history for future continuation."),
                new UserMessage(buildSummaryPrompt(messagesToText(messagesToCompress)))
        );

        String summaryContent;
        try {
            // 调用模型
            AgentStep step = request.modelAdapter().next(summaryRequestMessages);
            // AgentStep 只有两种类型，不是 assistantStep 就是 toolcalls step
            if (!(step instanceof AssistantStep assistantStep)) {
                return ManualCompactResult.failed(messages, "summary model returned tool calls");
            }
            summaryContent = assistantStep.content().trim();
            if (summaryContent.isBlank()) {
                return ManualCompactResult.failed(messages, "summary model returned empty content");
            }
        } catch (RuntimeException exception) {
            return ManualCompactResult.failed(messages, safeReason(exception));
        }

        ContextSummaryMessage summary = new ContextSummaryMessage(summaryContent, messagesToCompress.size(), now());
        List<ChatMessage> compactedMessages = new ArrayList<>();
        // 先将 SystemMessage 放进新的消息列表
        messages.stream().filter(SystemMessage.class::isInstance).forEach(compactedMessages::add);
        // 将 summary 放进消息列表
        compactedMessages.add(summary);
        // 将 boundary 之后的消息加入到列表，需要先把 usage 置为 stale
        compactedMessages.addAll(markRetainedUsageStale(messages.subList(boundary, messages.size()),
                request.trigger()));

        // 计算压缩后的消息列表的 token
        long tokensAfter = accountingService.account(compactedMessages).totalTokens();
        CompactMetadata metadata = new CompactMetadata(
                request.trigger(),
                tokensBefore,
                tokensAfter,
                messagesToCompress.size(),
                now()
        );
        return ManualCompactResult.compacted(List.copyOf(compactedMessages),
                new CompressionBoundaryResult(summary, metadata));
    }

    private Instant now() {
        return Instant.now(clock);
    }

    private static String safeReason(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message;
    }

    /**
     * 计算压缩时的保留边界。
     *
     * <p>返回值 {@code boundary} 表示从这条消息开始保留最近上下文；
     * {@code messages.subList(1, boundary)} 会被压缩成摘要，{@code boundary}
     * 及之后的消息会原样保留。计算时优先保留最近不超过 {@link #MAX_KEEP_TOKENS}
     * 的消息，同时保证至少保留最近 {@link #MIN_KEEP_MESSAGES} 条消息，最后再把边界
     * 对齐到完整工具调用轮次，避免把 tool call 和 tool result 切散。</p>
     */
    private int findRetentionBoundary(List<ChatMessage> messages) {
        long tokenSum = 0;
        int boundary = messages.size();
        // 从最新消息往前累计 token，尽量保留最近一段不超过 MAX_KEEP_TOKENS 的上下文。
        for (int index = messages.size() - 1; index >= 1; index--) {
            // 单条消息独立估算 token，用于判断继续往前保留是否会超过最近上下文预算。
            long tokens = accountingService.account(List.of(messages.get(index))).totalTokens();
            if (tokenSum + tokens > MAX_KEEP_TOKENS) {
                break;
            }
            tokenSum += tokens;
            // boundary 越小，表示保留区越往前扩展；这里把当前消息纳入保留区。
            boundary = index;
        }

        // 即使最近消息 token 很多，导致压缩边界很大，也至少保留最近 MIN_KEEP_MESSAGES 条，给模型留足局部上下文。
        int minBoundary = Math.max(1, messages.size() - MIN_KEEP_MESSAGES);
        // 从最近 MIN_KEEP_MESSAGES 和 MAX_KEEP_TOKENS得到的边界中取小的
        // ****越小，保留的消息越多
        boundary = Math.min(boundary, minBoundary);

        // 如果 boundary 太早，说明没有消息可以压缩，并且消息数量大于了最少保留消息数量
        // 就退回“保留最近 MIN_KEEP_MESSAGES 条”的边界，保证压缩有收益。
        if (boundary <= 1 && messages.size() > MIN_KEEP_MESSAGES + 1) {
            boundary = Math.max(1, messages.size() - MIN_KEEP_MESSAGES);
        }

        // 工具调用和工具结果必须作为完整轮次保留或压缩，不能把边界切在它们中间。
        return alignBoundaryToToolRound(messages, boundary);
    }

    /**
     * 将压缩保留边界对齐到完整工具调用轮次。
     *
     * <p>工具调用历史在 provider 协议中必须成对出现：assistant 发起的
     * {@link AssistantToolCallMessage} 需要有对应的 {@link ToolResultMessage}。
     * 如果边界落在同一轮工具调用中间，本方法会把边界前移到该轮开始位置，
     * 让这一轮工具调用整体保留，避免压缩后出现 dangling tool call/result。</p>
     *
     * @param messages 当前候选压缩消息列表
     * @param boundary 原始保留边界；{@code boundary} 及之后的消息会被保留
     * @return 对齐后的保留边界
     */
    private static int alignBoundaryToToolRound(List<ChatMessage> messages, int boundary) {
        int start = 0;
        while (start < messages.size()) {
            int cursor = start;
            // 某些 provider 会在工具调用前先返回 thinking；它应和后面的工具调用一起移动边界。
            if (messages.get(cursor) instanceof AssistantThinkingMessage) {
                cursor++;
            }

            // 一次模型响应可能包含多个并列工具调用，连续的 tool call 属于同一轮。
            while (cursor < messages.size() && messages.get(cursor) instanceof AssistantToolCallMessage) {
                cursor++;
            }
            // 紧随其后的工具结果也属于同一轮，必须和 tool call 一起保留或一起压缩。
            while (cursor < messages.size() && messages.get(cursor) instanceof ToolResultMessage) {
                cursor++;
            }

            if (cursor > start && hasToolRound(messages.subList(start, cursor))) {
                // 如果边界切进了 [start, cursor) 这轮工具调用中间，就前移到整轮开始。
                if (boundary > start && boundary < cursor) {
                    return start;
                }
                // 已识别出完整工具轮次，下一次扫描从轮次末尾继续。
                start = cursor;
                continue;
            }
            // 当前 start 不是工具轮次开头，继续尝试下一个位置。
            start++;
        }
        return boundary;
    }

    /**
     * 判断一段消息中是否包含工具调用轮次的核心消息。
     *
     * <p>{@link AssistantThinkingMessage} 可以作为工具调用前的上下文存在，但它本身不需要
     * provider tool 协议配对；只有 {@link AssistantToolCallMessage} 和
     * {@link ToolResultMessage} 才代表不能被切散的工具调用历史。</p>
     */
    private static boolean hasToolRound(List<ChatMessage> messages) {
        return messages.stream().anyMatch(message -> message instanceof AssistantToolCallMessage
                || message instanceof ToolResultMessage);
    }

    private static List<ChatMessage> markRetainedUsageStale(List<ChatMessage> messages, CompactTrigger trigger) {
        List<ChatMessage> result = new ArrayList<>(messages.size());
        String reason = switch (trigger) {
            case AUTO -> AUTO_STALE_REASON;
            case MANUAL -> MANUAL_STALE_REASON;
            case MICRO -> "conversation was compacted after this provider usage was recorded";
        };
        for (ChatMessage message : messages) {
            result.add(ChatMessages.markUsageStale(message, reason));
        }
        // 将消息的 usage 置为 stale 后返回
        return List.copyOf(result);
    }

    private static String messagesToText(List<ChatMessage> messages) {
        List<String> parts = new ArrayList<>();
        for (ChatMessage message : messages) {
            switch (message) {
                case UserMessage user -> parts.add("[User]: " + user.content());
                case AssistantMessage assistant -> parts.add("[Assistant]: " + assistant.content());
                case AssistantProgressMessage progress -> parts.add("[Assistant Progress]: " + progress.content());
                case AssistantThinkingMessage ignored ->
                        parts.add("[Assistant Thinking]: provider reasoning block existed and was omitted");
                case AssistantToolCallMessage toolCall ->
                        parts.add("[Tool Call: " + toolCall.toolName() + "]: " + toolCall.input());
                case ToolResultMessage toolResult -> parts.add("[Tool Result: " + toolResult.toolName()
                        + (toolResult.error() ? " ERROR" : "") + "]: " + preview(toolResult.content()));
                case ContextSummaryMessage summary -> parts.add("[Previous Summary]: " + summary.content());
                case SystemMessage ignored -> {
                }
                default -> {
                }
            }
        }
        return String.join("\n\n", parts);
    }

    private static String preview(String content) {
        if (content.length() <= TOOL_RESULT_SUMMARY_PREVIEW_CHARS) {
            return content;
        }
        return content.substring(0, TOOL_RESULT_SUMMARY_PREVIEW_CHARS) + "... (truncated)";
    }

    private static String buildSummaryPrompt(String conversationText) {
        return """
                Summarize the following MiniCode coding-agent conversation history so a future model can continue the same task.

                Preserve:
                - user goals and constraints
                - decisions already made
                - files, commands, tools, and results that matter
                - unresolved tasks and next steps
                - errors or failed attempts that should not be repeated

                Keep the summary concise but operational.

                Conversation:
                %s
                """.formatted(conversationText);
    }
}
