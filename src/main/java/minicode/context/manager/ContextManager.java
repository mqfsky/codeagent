package minicode.context.manager;

import minicode.core.message.ChatMessage;
import minicode.core.message.AssistantMessage;
import minicode.core.message.AssistantProgressMessage;
import minicode.core.message.AssistantToolCallMessage;
import minicode.core.message.ToolResultMessage;
import minicode.context.stats.ContextStats;
import minicode.model.UsageStaleness;
import minicode.tools.result.ToolResultBudgetResult;
import minicode.tools.result.ToolResultReplacementRecord;
import minicode.tools.result.ToolResultReplacementResult;
import minicode.tools.result.ToolResultReplacementTrigger;
import minicode.tools.result.ToolResultStorage;
import minicode.tools.result.ToolResultStorageRef;

import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * 上下文管理工具
 */
public class ContextManager {
    // microcompact 清理旧工具结果后，留在上下文里的占位文本, 为了告诉模型：这里原来有工具输出，但为了腾上下文空间已经清掉了；这个输出之前在本 session 里已经返回过。
    private static final String MICROCOMPACT_MARKER = "[Output cleared for context space. Full output was already returned earlier in this session.]";
    private static final double MICROCOMPACT_UTILIZATION = 0.50d;
    private static final int MICROCOMPACT_RETAIN_RECENT_RESULTS = 3;
    private static final java.util.Set<String> MICROCOMPACTABLE_TOOLS = java.util.Set.of(
            "read_file", "run_command", "list_files", "grep_files"
    );
    private final ToolResultStorage storage;
    private final int largeToolResultThreshold;
    private final int toolResultBatchBudget;
    private final int previewChars;
    private final boolean noOp;
    private final Map<ReplacementCacheKey, ToolResultReplacementRecord> replacementCache = new HashMap<>();

    public ContextManager(ToolResultStorage storage, int largeToolResultThreshold, int previewChars) {
        this(storage, largeToolResultThreshold, largeToolResultThreshold, previewChars);
    }

    public ContextManager(ToolResultStorage storage, int largeToolResultThreshold, int toolResultBatchBudget,
                          int previewChars) {
        this.storage = Objects.requireNonNull(storage, "storage");
        if (largeToolResultThreshold < 0) {
            throw new IllegalArgumentException("largeToolResultThreshold must be non-negative");
        }
        if (toolResultBatchBudget < 0) {
            throw new IllegalArgumentException("toolResultBatchBudget must be non-negative");
        }
        if (previewChars < 0) {
            throw new IllegalArgumentException("previewChars must be non-negative");
        }
        this.largeToolResultThreshold = largeToolResultThreshold;
        this.toolResultBatchBudget = toolResultBatchBudget;
        this.previewChars = previewChars;
        this.noOp = false;
    }

    private ContextManager() {
        this.storage = null;
        this.largeToolResultThreshold = Integer.MAX_VALUE;
        this.toolResultBatchBudget = Integer.MAX_VALUE;
        this.previewChars = 0;
        this.noOp = true;
    }

    public static ContextManager noOp() {
        return new ContextManager();
    }

    /**
     * 将单条过大的工具结果替换成可回填给模型的持久化引用消息。
     *
     * <p>模型下一轮仍然需要知道这个工具调用返回了什么，但完整输出可能非常长。
     * 当结果字符数超过 {@code largeToolResultThreshold} 时，本方法会把完整内容写入
     * {@link ToolResultStorage}，再把上下文里的 {@link ToolResultMessage} 替换成一个
     * {@code <persisted-output>} 摘要块。这样既保留了工具调用 id / 工具名的配对关系，
     * 又避免把巨大输出直接塞进模型上下文。</p>
     *
     * <p>例如一次 {@code read_file} 工具调用 id 为 {@code toolu_123}，原始输出有
     * {@code 534210} 个字符，写入存储后得到 {@code storageRef.id() = "8f3c..."}
     * 和 {@code storageRef.path() = "~/.codeagent/tool-results/8f3c....txt"}，
     * 预览长度为 {@code previewChars}。替换后的消息内容大致会是：</p>
     *
     * <pre>{@code
     * <persisted-output toolUseId="toolu_123" toolName="read_file">
     * STORAGE_REF: 8f3c...
     * PATH: ~/.codeagent/tool-results/8f3c....txt
     * BYTES: 612345
     * ORIGINAL_CHARS: 534210
     * PREVIEW:
     * 文件开头的一小段原始输出...
     * </persisted-output>
     * }</pre>
     *
     * @param message 原始工具结果消息
     * @return 如果输出过大，返回替换后的消息和替换记录；否则返回原消息且替换记录为空
     */
    public ToolResultReplacementResult replaceLargeToolResult(ToolResultMessage message) {
        ToolResultMessage actualMessage = Objects.requireNonNull(message, "message");
        // noOp 模式用于测试或不启用上下文治理的场景，必须保持原消息不变。
        if (noOp) {
            return new ToolResultReplacementResult(actualMessage, Optional.empty());
        }
        String content = actualMessage.content();
        // 小输出可以直接进上下文；已经是 persisted-output 的内容也不能重复持久化（表示已经被持久化机制处理过了)）。
        if (content.length() <= largeToolResultThreshold || isPersistedOutput(content)) {
            return new ToolResultReplacementResult(actualMessage, Optional.empty());
        }

        // 超过阈值时，将完整输出写入 tool-results，并生成带预览的替换记录。
        ToolResultReplacementRecord record = replacementFor(
                actualMessage,
                content,
                ToolResultReplacementTrigger.SINGLE_RESULT_TOO_LARGE
        );
        // 保留 toolUseId/toolName/error，只替换 content，保证后续模型仍能按工具调用 id 配对。
        ToolResultMessage replacementMessage = new ToolResultMessage(
                actualMessage.toolUseId(),
                actualMessage.toolName(),
                record.replacementContent(),
                actualMessage.error()
        );
        return new ToolResultReplacementResult(replacementMessage, Optional.of(record));
    }

    public ToolResultBudgetResult applyToolResultBudget(List<ToolResultMessage> results) {
        List<ToolResultMessage> actualResults = List.copyOf(Objects.requireNonNull(results, "results"));
        if (noOp || actualResults.isEmpty()) {
            return new ToolResultBudgetResult(actualResults, List.of());
        }

        // 计算本轮工具结果的总字符数
        int totalChars = actualResults.stream().mapToInt(message -> message.content().length()).sum();
        if (totalChars <= toolResultBatchBudget) {
            return new ToolResultBudgetResult(actualResults, List.of());
        }

        List<ToolResultMessage> budgetedResults = new ArrayList<>(actualResults);
        List<ToolResultReplacementRecord> replacements = new ArrayList<>();
        List<Integer> candidateIndexes = new ArrayList<>();
        // 过滤掉已经替换过文本的工具输出
        for (int index = 0; index < budgetedResults.size(); index++) {
            if (!isPersistedOutput(budgetedResults.get(index).content())) {
                candidateIndexes.add(index);
            }
        }

        // 按照内容长度从大到小排序
        candidateIndexes.sort(Comparator
                .comparingInt((Integer index) -> budgetedResults.get(index).content().length())
                .reversed());

        for (int index : candidateIndexes) {
            if (totalChars <= toolResultBatchBudget && !replacements.isEmpty()) {
                break;
            }
            ToolResultMessage original = budgetedResults.get(index);
            String originalContent = original.content();
            ToolResultReplacementRecord replacement = replacementFor(
                    original,
                    originalContent,
                    ToolResultReplacementTrigger.BATCH_BUDGET_EXCEEDED
            );
            ToolResultMessage replacementMessage = new ToolResultMessage(
                    original.toolUseId(),
                    original.toolName(),
                    replacement.replacementContent(),
                    original.error()
            );
            budgetedResults.set(index, replacementMessage);
            replacements.add(replacement);
            totalChars = totalChars - originalContent.length() + replacementMessage.content().length();
        }

        return new ToolResultBudgetResult(budgetedResults, replacements);
    }

    public List<ChatMessage> microcompact(List<ChatMessage> messages) {
        return List.copyOf(Objects.requireNonNull(messages, "messages"));
    }

    public List<ChatMessage> microcompact(List<ChatMessage> messages, ContextStats stats) {
        List<ChatMessage> actualMessages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        Objects.requireNonNull(stats, "stats");
        if (noOp || actualMessages.isEmpty()) {
            return actualMessages;
        }
        // 如果当前输入占有效输入上限的比例 未达到阈值 则不进行压缩
        if (stats.utilization() < MICROCOMPACT_UTILIZATION) {
            return actualMessages;
        }
        List<Integer> compactableIndexes = new ArrayList<>();
        // 筛选需要压缩的消息
        for (int index = 0; index < actualMessages.size(); index++) {
            ChatMessage message = actualMessages.get(index);
            // 必须是工具执行结果
            if (message instanceof ToolResultMessage toolResult
                    // 必须是允许清理的工具
                    && MICROCOMPACTABLE_TOOLS.contains(toolResult.toolName())
                    // 不能是已经持久化的输出，当调用工具得到的消息太大，会进行持久化，并留下这条标记
                    && !toolResult.content().startsWith("<persisted-output ")
                    // 不能是已经被 microcompact 清理过的结果
                    && !MICROCOMPACT_MARKER.equals(toolResult.content())) {
                // 加入到待压缩列表
                compactableIndexes.add(index);
            }
        }

        // 需要清除的数量，仅保留最近 3 条
        int clearCount = compactableIndexes.size() - MICROCOMPACT_RETAIN_RECENT_RESULTS;
        if (clearCount <= 0) {
            return actualMessages;
        }

        List<ChatMessage> compacted = new ArrayList<>(actualMessages);
        boolean changed = false;
        for (int index = 0; index < clearCount; index++) {
            int messageIndex = compactableIndexes.get(index);
            ToolResultMessage original = (ToolResultMessage) compacted.get(messageIndex);
            // 将内容直接替换为占位符
            compacted.set(messageIndex, new ToolResultMessage(
                    original.toolUseId(),
                    original.toolName(),
                    MICROCOMPACT_MARKER, // 可能存在损失记忆的问题
                    original.error()
            ));
            changed = true;
        }
        // 更新消息 usage
        return changed ? List.copyOf(markProviderUsageStale(compacted)) : actualMessages;
    }

    /**
     * 当上下文里的消息内容被 microcompact 改写后，把历史里已有的 provider usage 标记为 stale，避免后续继续信任旧 token 统计。
     * @param messages
     * @return
     */

    private List<ChatMessage> markProviderUsageStale(List<ChatMessage> messages) {
        List<ChatMessage> result = new ArrayList<>(messages.size());
        String reason = "tool_result content was microcompacted after this provider usage was recorded";
        // 遍历所有消息
        for (ChatMessage message : messages) {
            // 重新构造 messages
            result.add(markProviderUsageStale(message, reason));
        }
        return result;
    }

    /**
     * 只处理三类可能带 provider usage 的消息：
     * AssistantMessage
     * AssistantProgressMessage
     * AssistantToolCallMessage
     * 如果满足有 providerUsage 并且 usageStaleness 还不是 stale，就创建一条新的消息，把原来的内容、usage 都保留，但把 UsageStaleness 改成：stale
     * @param message
     * @param reason
     * @return
     */
    private ChatMessage markProviderUsageStale(ChatMessage message, String reason) {
        UsageStaleness staleness = UsageStaleness.stale(reason);
        return switch (message) {
            case AssistantMessage assistant when assistant.providerUsage().isPresent()
                    && !assistant.usageStaleness().stale() ->
                    new AssistantMessage(assistant.content(), assistant.providerUsage(), staleness);
            case AssistantProgressMessage progress when progress.providerUsage().isPresent()
                    && !progress.usageStaleness().stale() ->
                    new AssistantProgressMessage(progress.content(), progress.providerUsage(), staleness);
            case AssistantToolCallMessage toolCall when toolCall.providerUsage().isPresent()
                    && !toolCall.usageStaleness().stale() ->
                    new AssistantToolCallMessage(toolCall.toolUseId(), toolCall.toolName(), toolCall.input(),
                            toolCall.providerUsage(), staleness);
            default -> message;
        };
    }

    private ToolResultReplacementRecord replacementFor(ToolResultMessage message, String content,
                                                       ToolResultReplacementTrigger trigger) {
        ReplacementCacheKey key = new ReplacementCacheKey(
                message.toolUseId(),
                message.toolName(),
                contentHash(content),
                trigger
        );
        ToolResultReplacementRecord cached = replacementCache.get(key);
        if (cached != null) {
            return cached;
        }

        // 文件存到本地
        ToolResultStorageRef storageRef = Objects.requireNonNull(storage, "storage").store(content);
        // 取开头一段原始输出
        String preview = preview(content);
        // 拼接内容
        String replacementContent = replacementContent(message, storageRef, content, preview);
        ToolResultReplacementRecord record = new ToolResultReplacementRecord(
                message.toolUseId(),
                message.toolName(),
                trigger,
                storageRef,
                replacementContent,
                preview,
                content.length(),
                preview.length(),
                replacementContent.length()
        );
        replacementCache.put(key, record);
        return record;
    }

    private String replacementContent(ToolResultMessage message, ToolResultStorageRef storageRef, String content,
                                      String preview) {
        return String.join("\n",
                "<persisted-output toolUseId=\"" + message.toolUseId() + "\" toolName=\"" + message.toolName() + "\">",
                "STORAGE_REF: " + storageRef.id(),
                "PATH: " + storageRef.path(),
                "BYTES: " + storageRef.bytes(),
                "ORIGINAL_CHARS: " + content.length(),
                "PREVIEW:",
                preview,
                "</persisted-output>"
        );
    }

    private String preview(String content) {
        return content.substring(0, Math.min(previewChars, content.length()));
    }

    private static boolean isPersistedOutput(String content) {
        return content.startsWith("<persisted-output ");
    }

    private static String contentHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * 大工具输出替换记录的缓存键。
     *
     * @param toolUseId 所属工具调用 id
     * @param toolName 工具名称
     * @param contentHash 原始内容的哈希值
     * @param trigger 压缩触发来源
     */
    private record ReplacementCacheKey(String toolUseId, String toolName, String contentHash,
                                       ToolResultReplacementTrigger trigger) {
        private ReplacementCacheKey {
            requireText(toolUseId, "toolUseId");
            requireText(toolName, "toolName");
            requireText(contentHash, "contentHash");
            trigger = Objects.requireNonNull(trigger, "trigger");
        }

        private static void requireText(String value, String name) {
            if (Objects.requireNonNull(value, name).isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
        }
    }
}
