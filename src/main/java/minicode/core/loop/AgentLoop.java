package minicode.core.loop;

import minicode.context.accounting.TokenAccountingService;
import minicode.context.compact.AutoCompactController;
import minicode.context.compact.AutoCompactEventType;
import minicode.context.compact.AutoCompactResult;
import minicode.context.compact.CompactStatus;
import minicode.context.manager.ContextManager;
import minicode.context.stats.ContextStats;
import minicode.context.stats.ContextStatsCalculator;
import minicode.context.stats.ModelContextWindow;
import minicode.core.event.AgentEvent;
import minicode.core.event.AgentEventSink;
import minicode.core.event.ToolResultsBudgetedEvent;
import minicode.core.message.*;
import minicode.core.step.AgentStep;
import minicode.core.step.AssistantStep;
import minicode.core.step.ToolCallsStep;
import minicode.core.turn.*;
import minicode.model.UsageStaleness;
import minicode.model.ModelRequestException;
import minicode.session.plan.PersistenceAction;
import minicode.session.plan.TurnPersistencePlan;
import minicode.tools.api.ToolCall;
import minicode.tools.api.ToolContext;
import minicode.tools.api.ToolExecutor;
import minicode.tools.result.ToolResult;
import minicode.tools.result.ToolResultBudgetResult;
import minicode.tools.result.ToolResultReplacementResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class AgentLoop {
    private static final String EMPTY_RESPONSE_MESSAGE =
            "The model returned an empty response after retries. Please try again.";
    private static final String PROGRESS_CONTINUATION_PROMPT =
            "Continue immediately from your <progress> update with concrete tool calls, code changes, or an explicit <final> answer only if the task is complete.";
    private static final String EMPTY_RESPONSE_CONTINUATION_PROMPT =
            "Your last response was empty. Continue immediately with concrete tool calls, code changes, or an explicit <final> answer only if the task is complete.";
    private static final String EMPTY_RESPONSE_AFTER_TOOL_RESULT_CONTINUATION_PROMPT =
            "Your last response was empty after recent tool results. Continue immediately by trying the next concrete step, adapting to any tool errors, or giving an explicit <final> answer only if the task is complete.";
    private static final String EMPTY_RESPONSE_AFTER_TOOL_ERROR_CONTINUATION_PROMPT =
            "Your last response was empty after recent tool results that included errors. Adapt to the tool error, try the next concrete step, or give an explicit <final> answer only if the task is complete.";
    private static final String MAX_TOKENS_THINKING_CONTINUATION_PROMPT =
            "Your previous response hit max_tokens during thinking before producing the next actionable step. Resume immediately and continue with the next concrete tool call, code change, or an explicit <final> answer only if the task is complete. Do not repeat the earlier plan.";
    private static final String PAUSE_TURN_THINKING_CONTINUATION_PROMPT =
            "Resume from the previous pause_turn and continue the task immediately. Produce the next concrete tool call, code change, or an explicit <final> answer only if the task is complete.";
    private static final int MODEL_REQUEST_ATTEMPTS = 3;

    private final ModelAdapter modelAdapter;
    private final AgentEventSink eventSink;
    private final ToolExecutor toolExecutor;
    private final ContextManager contextManager;
    private final ContextStatsCalculator contextStatsCalculator;
    private final AutoCompactController autoCompactController;
    private final int maxEmptyResponseRetries;

    public AgentLoop(ModelAdapter modelAdapter, AgentEventSink eventSink) {
        this(modelAdapter, eventSink, ToolExecutor.unsupported(), 2);
    }

    public AgentLoop(ModelAdapter modelAdapter, AgentEventSink eventSink, int maxEmptyResponseRetries) {
        this(modelAdapter, eventSink, ToolExecutor.unsupported(), maxEmptyResponseRetries);
    }

    public AgentLoop(ModelAdapter modelAdapter, AgentEventSink eventSink, ToolExecutor toolExecutor) {
        this(modelAdapter, eventSink, toolExecutor, 2);
    }

    public AgentLoop(ModelAdapter modelAdapter, AgentEventSink eventSink, ToolExecutor toolExecutor,
                     ContextManager contextManager) {
        this(modelAdapter, eventSink, toolExecutor, contextManager, 2);
    }

    public AgentLoop(ModelAdapter modelAdapter, AgentEventSink eventSink, ToolExecutor toolExecutor,
                     int maxEmptyResponseRetries) {
        this(modelAdapter, eventSink, toolExecutor, ContextManager.noOp(), maxEmptyResponseRetries);
    }

    public AgentLoop(ModelAdapter modelAdapter, AgentEventSink eventSink, ToolExecutor toolExecutor,
                     ContextManager contextManager, int maxEmptyResponseRetries) {
        this(modelAdapter, eventSink, toolExecutor, contextManager, defaultContextStatsCalculator(),
                AutoCompactController.disabled(), maxEmptyResponseRetries);
    }

    public AgentLoop(ModelAdapter modelAdapter, AgentEventSink eventSink, ToolExecutor toolExecutor,
                     ContextManager contextManager, ContextStatsCalculator contextStatsCalculator) {
        this(modelAdapter, eventSink, toolExecutor, contextManager, contextStatsCalculator, 2);
    }

    public AgentLoop(ModelAdapter modelAdapter, AgentEventSink eventSink, ToolExecutor toolExecutor,
                     ContextManager contextManager, ContextStatsCalculator contextStatsCalculator,
                     int maxEmptyResponseRetries) {
        this(modelAdapter, eventSink, toolExecutor, contextManager, contextStatsCalculator,
                AutoCompactController.disabled(), maxEmptyResponseRetries);
    }

    public AgentLoop(ModelAdapter modelAdapter, AgentEventSink eventSink, ToolExecutor toolExecutor,
                     ContextManager contextManager, ContextStatsCalculator contextStatsCalculator,
                     AutoCompactController autoCompactController) {
        this(modelAdapter, eventSink, toolExecutor, contextManager, contextStatsCalculator,
                autoCompactController, 2);
    }

    public AgentLoop(ModelAdapter modelAdapter, AgentEventSink eventSink, ToolExecutor toolExecutor,
                     ContextManager contextManager, ContextStatsCalculator contextStatsCalculator,
                     AutoCompactController autoCompactController,
                     int maxEmptyResponseRetries) {
        this.modelAdapter = Objects.requireNonNull(modelAdapter, "modelAdapter");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "toolExecutor");
        this.contextManager = Objects.requireNonNull(contextManager, "contextManager");
        this.contextStatsCalculator = Objects.requireNonNull(contextStatsCalculator, "contextStatsCalculator");
        this.autoCompactController = Objects.requireNonNull(autoCompactController, "autoCompactController");
        if (maxEmptyResponseRetries < 0) {
            throw new IllegalArgumentException("maxEmptyResponseRetries must be non-negative");
        }
        this.maxEmptyResponseRetries = maxEmptyResponseRetries;
    }

    public AgentTurnResult runTurn(AgentTurnRequest request) {
        Objects.requireNonNull(request, "request");

        // 本轮 turn 的工作副本：messages 会在 loop 中不断追加模型输出、工具调用和工具结果。
        // 当前 message 中以及存在 systemPrompt 以及历史记录以及当前用户问题
        List<ChatMessage> messages = new ArrayList<>(request.messages());

        // AgentLoop 不直接写 session 文件，只收集持久化动作，最后交给调用方落盘。
        List<PersistenceAction> actions = new ArrayList<>();
        // 记录空响应次数
        int emptyResponseCount = 0;
        // 记录 thinking 被截断后恢复的次数
        int recoverableThinkingRetryCount = 0;
        boolean sawToolResultThisTurn = false;
        int toolErrorCount = 0;

        try {
            //  throwIfCancellationRequested 是 AgentLoop 的取消检查点。它让用户或系统可以在模型请求、工具执行、消息追加等阶段及时中断当前 turn，并把取消原因记录成结构化结果。
            request.cancellationToken().throwIfCancellationRequested(CancellationPhase.BEFORE_TURN);

            // 一个 turn 里可以有多个 step：每个 step 最多进行一次模型请求，并可能触发一组工具调用。
            for (int stepIndex = 0; stepIndex < request.maxSteps(); stepIndex++) {
                // 1.模型请求前先处理上下文压力：统计 -> microcompact -> 再统计 -> 必要时 autoCompact。
                // 上下文状态，统计当前 message 占了多少 token，算上下文窗口占用率和警告等级
                ContextStats preCompactStats = contextStatsCalculator.calculate(List.copyOf(messages));

                // 压缩，轻量清理旧工具结果
                messages = new ArrayList<>(contextManager.microcompact(List.copyOf(messages), preCompactStats));

                // 再统计
                ContextStats stats = contextStatsCalculator.calculate(List.copyOf(messages));

                // 真正请求模型前，再尝试自动压缩
                AutoCompactResult autoCompactResult = runAutoCompactPreflight(request.turnId(), messages, actions, stats);
                if (autoCompactResult.status() == CompactStatus.COMPACTED) {
                    messages = new ArrayList<>(autoCompactResult.messages());
                    // 更新窗口状态
                    stats = contextStatsCalculator.calculate(List.copyOf(messages));
                }
                // 然后发布上下文状态事件,TUI 可以用它显示上下文占用情况。
                publishEvent(new AgentEvent.ContextStatsEvent(request.turnId(), Instant.now(), stats));
                request.cancellationToken().throwIfCancellationRequested(CancellationPhase.MODEL_REQUEST);

                // 2.真正调用模型：Adapter 会把 messages 翻译成 provider 请求，并返回统一的 AgentStep。
                AgentStep step;
                try {
                    step = nextWithRetries(List.copyOf(messages), request.cancellationToken());
                    request.cancellationToken().throwIfCancellationRequested(CancellationPhase.MODEL_REQUEST);
                } catch (CancellationRequestedException exception) {
                    throw exception;
                } catch (ModelRequestException exception) {
                    return modelErrorResult(messages, actions, exception);
                } catch (RuntimeException exception) {
                    return modelErrorResult(messages, actions,
                            exception.getMessage() == null || exception.getMessage().isBlank()
                                    ? "Model adapter failed"
                                    : exception.getMessage(),
                            exception.getClass().getName());
                }

                if (step == null) {
                    return modelErrorResult(messages, actions,
                            "Model adapter returned null AgentStep",
                            NullPointerException.class.getName());
                }
                // 一共两种分支ToolCallsStep和AssistantStep

                // 4.分支一：模型要求调用工具。先把 tool call 写入上下文，再执行工具并收集结果。
                if (step instanceof ToolCallsStep toolCallsStep) {
                    // 先记录模型说了什么
                    appendToolCallsStepProjection(request.turnId(), messages, actions, toolCallsStep);
                    request.cancellationToken().throwIfCancellationRequested(CancellationPhase.AFTER_TURN);
                    List<ToolResultMessage> toolResultMessages = new ArrayList<>();

                    // 先记录所有工具调用，存入 action 以及 message，让下一次模型请求能看到 assistant 发起了哪些 tool_use。
                    for (int callIndex = 0; callIndex < toolCallsStep.calls().size(); callIndex++) {
                        ToolCall call = toolCallsStep.calls().get(callIndex);
                        // 先把模型发起的工具调用写进上下文，把每次 toolcall 变成 AssistantToolCallMessage
                        appendToolCallMessage(request.turnId(), messages, actions, call,
                                callIndex == toolCallsStep.calls().size() - 1
                                        ? toolCallsStep.usage()
                                        : Optional.empty());
                        // 发布事件给 UI 使得界面上能显示 tool call 操作
                        publishEvent(new AgentEvent.ToolStartedEvent(request.turnId(), Instant.now(),
                                call.id(), call.toolName(), call.input()));
                        request.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);
                    }

                    // 再逐个执行工具；具体权限校验发生在 ToolRegistry/具体 Tool 内部。
                    for (ToolCall call : toolCallsStep.calls()) {
                        request.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);

                        ToolResult result;
                        try {
                            // 执行，内部流程是什么样的？得到结果：ok or error
                            result = toolExecutor.execute(call, createToolContext(request, call.id()));
                            request.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);
                        } catch (CancellationRequestedException exception) {
                            throw exception;
                        } catch (RuntimeException exception) {
                            result = ToolResult.error(exception.getMessage() == null || exception.getMessage().isBlank()
                                    ? "Tool execution failed"
                                    : exception.getMessage());
                        }

                        if (result == null) {
                            result = ToolResult.error("Tool executor returned null ToolResult");
                        }
                        sawToolResultThisTurn = true;
                        if (result.error()) {
                            // 记录工具错误次数
                            toolErrorCount++;
                        }

                        // 工具结果会变成 ToolResultMessage，追加回 messages，供下一轮 step 继续推理。
                        ToolResultMessage toolResultMessage = appendToolResultMessage(request.turnId(), messages, actions, call, result);
                        toolResultMessages.add(toolResultMessage);

                        request.cancellationToken().throwIfCancellationRequested(CancellationPhase.AFTER_TURN);
                        // ask_user 这类工具会让 turn 暂停，等待用户补充输入后再继续。
                        if (result.awaitUser()) {
                            applyToolResultBudget(request.turnId(), messages, actions, toolResultMessages);
                            request.cancellationToken().throwIfCancellationRequested(CancellationPhase.AFTER_TURN);
                            publishEvent(new AgentEvent.AwaitUserEvent(
                                    request.turnId(),
                                    Instant.now(),
                                    call.id(),
                                    awaitUserQuestion(call.id(), toolResultMessages)
                            ));
                            return AgentTurnResult.awaitUser(List.copyOf(messages), new TurnPersistencePlan(actions));
                        }
                    }

                    // 工具输出可能很大，这里统一做预算控制，然后继续下一个 step 再问模型。
                    applyToolResultBudget(request.turnId(), messages, actions, toolResultMessages);
                    request.cancellationToken().throwIfCancellationRequested(CancellationPhase.AFTER_TURN);
                    continue;
                }

                // 分支二：模型没有要求工具调用，而是返回 assistant 文本或进度, assistantStep 说明模型返回的是文本类输出，不是工具调用。
                // 防御性检查
                if (!(step instanceof AssistantStep assistantStep)) {
                    return modelErrorResult(messages, actions,
                            "AgentLoop only supports AssistantStep and ToolCallsStep",
                            step.getClass().getName());
                }

                // 5.thinking 被 max_tokens / pause_turn 截断时，不结束 turn，而是追加续跑提示继续推进。
                if (isRecoverableThinkingStop(assistantStep) && recoverableThinkingRetryCount < 3) {
                    recoverableThinkingRetryCount++;
                    String stopReason = assistantStep.diagnostics().orElseThrow().stopReason().orElse("");
                    AssistantProgressMessage progressMessage = new AssistantProgressMessage(
                            "max_tokens".equals(stopReason)
                                    ? "Model hit max_tokens during thinking; requesting the next actionable step."
                                    : "Model returned pause_turn during thinking; requesting the next actionable step."
                    );
                    // 追加原因，让模型继续跑
                    appendMessage(request.turnId(), messages, actions, progressMessage);
                    request.cancellationToken().throwIfCancellationRequested(CancellationPhase.AFTER_TURN);
                    appendMessage(request.turnId(), messages, actions, new UserMessage(
                            "max_tokens".equals(stopReason)
                                    ? MAX_TOKENS_THINKING_CONTINUATION_PROMPT
                                    : PAUSE_TURN_THINKING_CONTINUATION_PROMPT
                    ));
                    request.cancellationToken().throwIfCancellationRequested(CancellationPhase.AFTER_TURN);
                    continue;
                }

                // 6.空响应通常是 provider 或模型异常状态；有限重试后仍为空，就生成 fallback 结果结束。
                // 模型没有给工具调用
                // 也没有给最终文本
                // 也不是可恢复 thinking stop
                if (assistantStep.content().isBlank()) {
                    emptyResponseCount++;
                    // 没超过次数，加上 prompt 后进行重试
                    if (emptyResponseCount <= maxEmptyResponseRetries) {
                        appendMessage(request.turnId(), messages, actions,
                                new UserMessage(emptyResponseContinuationPrompt(sawToolResultThisTurn, toolErrorCount)));
                        request.cancellationToken().throwIfCancellationRequested(CancellationPhase.AFTER_TURN);
                        continue;
                    }

                    appendAssistantThinkingBlocks(request.turnId(), messages, actions, assistantStep);
                    String fallbackReason = emptyFallbackReason(sawToolResultThisTurn, toolErrorCount);

                    // 生成 fallback
                    AssistantMessage fallbackMessage = new AssistantMessage(
                            emptyFallbackMessage(sawToolResultThisTurn, toolErrorCount, assistantStep));
                    // 添加 fallback 消息
                    appendMessage(request.turnId(), messages, actions, fallbackMessage);

                    request.cancellationToken().throwIfCancellationRequested(CancellationPhase.AFTER_TURN);
                    // 返回给 miniUI 进行展示、落盘等
                    return AgentTurnResult.emptyFallback(
                            List.copyOf(messages),
                            new TurnPersistencePlan(actions),
                            Optional.of(new EmptyFallbackDetails(
                                    Optional.of(fallbackReason),
                                    Optional.of(emptyFallbackDiagnostics(assistantStep, sawToolResultThisTurn,
                                            toolErrorCount)),
                                    sawToolResultThisTurn,
                                    toolErrorCount
                            ))
                    );
                }
                // 重置次数
                emptyResponseCount = 0;

                // 7.FINAL/UNSPECIFIED 表示本轮完成；PROGRESS 只是中间进展，需要追加续跑提示继续下一 step。
                switch (assistantStep.kind()) {
                    case FINAL, UNSPECIFIED -> {
                        appendAssistantThinkingBlocks(request.turnId(), messages, actions, assistantStep);
                        AssistantMessage finalMessage = new AssistantMessage(
                                assistantStep.content(),
                                assistantStep.usage(),
                                UsageStaleness.fresh()
                        );
                        appendMessage(request.turnId(), messages, actions, finalMessage);
                        request.cancellationToken().throwIfCancellationRequested(CancellationPhase.AFTER_TURN);
                        return AgentTurnResult.finalResult(List.copyOf(messages), new TurnPersistencePlan(actions));
                    }
                    case PROGRESS -> {
                        appendAssistantThinkingBlocks(request.turnId(), messages, actions, assistantStep);
                        AssistantProgressMessage progressMessage = new AssistantProgressMessage(
                                assistantStep.content(),
                                assistantStep.usage(),
                                UsageStaleness.fresh()
                        );
                        appendMessage(request.turnId(), messages, actions, progressMessage);
                        request.cancellationToken().throwIfCancellationRequested(CancellationPhase.AFTER_TURN);
                        appendMessage(request.turnId(), messages, actions, new UserMessage(PROGRESS_CONTINUATION_PROMPT));
                        request.cancellationToken().throwIfCancellationRequested(CancellationPhase.AFTER_TURN);
                    }
                }
            }

            // 8.step 次数耗尽但还没 final，返回 MAX_STEPS，让上层提示用户是否继续。
            request.cancellationToken().throwIfCancellationRequested(CancellationPhase.AFTER_TURN);
            return AgentTurnResult.maxSteps(List.copyOf(messages), new TurnPersistencePlan(actions));
        } catch (CancellationRequestedException exception) {
            // 任何阶段收到取消信号，都把当前 messages/actions 打包成 cancelled result 返回。
            return cancelledResult(request.turnId(), messages, actions, exception.cancellation());
        }
    }

    private AgentStep nextWithRetries(List<ChatMessage> messages, CancellationToken cancellationToken) {
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= MODEL_REQUEST_ATTEMPTS; attempt++) {
            try {
                return modelAdapter.next(messages);
            } catch (CancellationRequestedException exception) {
                throw exception;
            } catch (ModelRequestException exception) {
                lastException = exception;
            } catch (RuntimeException exception) {
                lastException = exception;
            }
            cancellationToken.throwIfCancellationRequested(CancellationPhase.MODEL_REQUEST);
        }
        throw Objects.requireNonNull(lastException, "lastException");
    }

    private static ContextStatsCalculator defaultContextStatsCalculator() {
        return new ContextStatsCalculator(new TokenAccountingService(), new ModelContextWindow(128_000, 8_000));
    }

    private AutoCompactResult runAutoCompactPreflight(String turnId, List<ChatMessage> messages,
                                                      List<PersistenceAction> actions, ContextStats stats) {
        // 判断“是否可能尝试自动压缩”
        if (autoCompactController.willAttempt(List.copyOf(messages), stats)) {
            publishEvent(new AgentEvent.AutoCompactEvent(turnId, Instant.now(),
                    AutoCompactEventType.STARTED, Optional.empty(), Optional.empty()));
        }

        // 进入压缩流程
        AutoCompactResult result = autoCompactController.preflight(List.copyOf(messages), stats, modelAdapter);
        switch (result.status()) {
            case COMPACTED -> {
                publishEvent(new AgentEvent.AutoCompactEvent(turnId, Instant.now(),
                        AutoCompactEventType.COMPLETED, Optional.of(result.compressionResult()), Optional.empty()));
                appendAutoCompactPersistenceActions(actions, result);
            }
            case FAILED -> {
                publishEvent(new AgentEvent.AutoCompactEvent(turnId, Instant.now(),
                        AutoCompactEventType.FAILED, Optional.empty(),
                        Optional.of(result.reason().orElse("auto compact failed"))));
            }
            case SKIPPED -> publishAutoCompactSkippedIfRelevant(turnId, result);
        }
        return result;
    }

    private void publishAutoCompactSkippedIfRelevant(String turnId, AutoCompactResult result) {
        String reason = result.reason().orElse("");
        if (reason.contains("below auto compact threshold")
                || reason.contains("below auto compact minimum")
                || reason.contains("auto compact disabled")) {
            return;
        }
        publishEvent(new AgentEvent.AutoCompactEvent(turnId, Instant.now(),
                AutoCompactEventType.SKIPPED, Optional.empty(), Optional.of(reason)));
    }

    private void appendAutoCompactPersistenceActions(List<PersistenceAction> actions, AutoCompactResult result) {
        minicode.context.compact.CompressionBoundaryResult boundary = result.boundary().orElseThrow();

        actions.add(new PersistenceAction.AppendCompactBoundaryAction(boundary.summaryMessage(), boundary.metadata()));

        List<ChatMessage> retainedMessages = retainedMessagesAfterCompactBoundary(result.messages(), boundary.summaryMessage());
        if (!retainedMessages.isEmpty()) {
            actions.add(new PersistenceAction.AppendMessagesAction(retainedMessages));
        }
    }

    private List<ChatMessage> retainedMessagesAfterCompactBoundary(List<ChatMessage> compactedMessages,
                                                                   ChatMessage summaryMessage) {
        boolean skippedSummary = false;
        List<ChatMessage> retained = new ArrayList<>();
        for (ChatMessage message : compactedMessages) {
            if (message instanceof SystemMessage) {
                continue;
            }
            if (!skippedSummary && message.equals(summaryMessage)) {
                skippedSummary = true;
                continue;
            }
            retained.add(message);
        }
        return List.copyOf(retained);
    }

    /**
     * 将一条消息同时追加到内存上下文和本轮持久化计划中。
     *
     * <p>这是 loop 内部新增消息的统一入口：后续 step 中模型能看到这条消息，
     * turn 结束后 session runner 能把它落盘，UI 也能通过事件流展示出来。</p>
     */
    private void appendMessage(String turnId, List<ChatMessage> messages, List<PersistenceAction> actions,
                               ChatMessage message) {
        messages.add(message);
        actions.add(new PersistenceAction.AppendMessagesAction(List.of(message)));
        publishEvent(new AgentEvent.AssistantMessageEvent(turnId, Instant.now(), message));
    }

    /**
     * 如果 provider 返回了 thinking blocks，就把它们保存为独立的 assistant thinking 消息。
     *
     * <p>thinking 与普通 assistant 文本分开存放，后续 provider 请求可以按 provider
     * 协议回放这些推理块，同时不把它们混进最终回答文本里。</p>
     */
    private void appendAssistantThinkingBlocks(String turnId, List<ChatMessage> messages,
                                               List<PersistenceAction> actions, AssistantStep step) {
        if (!step.thinkingBlocks().isEmpty()) {
            appendMessage(turnId, messages, actions, new AssistantThinkingMessage(step.thinkingBlocks()));
        }
    }

    /**
     * 在真正执行工具之前，先把模型请求的工具调用记录进上下文。
     *
     * <p>生成的 {@link AssistantToolCallMessage} 对应 provider 返回的 tool_use block。
     * 把它保存在 {@code messages} 中，下一次模型请求才能用同一个 tool call id
     * 将工具结果和原始工具调用配对起来。</p>
     */
    private void appendToolCallMessage(String turnId, List<ChatMessage> messages, List<PersistenceAction> actions,
                                       ToolCall call, Optional<minicode.model.ProviderUsage> usage) {
        AssistantToolCallMessage message = new AssistantToolCallMessage(
                call.id(),
                call.toolName(),
                call.input(),
                usage,
                UsageStaleness.fresh()
        );
        // 将一条消息同时追加到内存上下文和本轮持久化计划中
        appendMessage(turnId, messages, actions, message);
    }

    private ToolResultMessage appendToolResultMessage(String turnId, List<ChatMessage> messages,
                                                      List<PersistenceAction> actions, ToolCall call,
                                                      ToolResult result) {
        // 工具结果 message
        ToolResultMessage originalMessage = new ToolResultMessage(call.id(), call.toolName(), result.content(), result.error());
        // 检查工具输出是不是太大, 如果工具结果特别大，比如读了一个巨大文件、命令输出几十万字符，不能直接塞进上下文，否则会挤爆模型窗口。
        ToolResultReplacementResult replacementResult = contextManager.replaceLargeToolResult(originalMessage);

        ToolResultMessage message = replacementResult.message();
        appendMessage(turnId, messages, actions, message);
        publishEvent(new AgentEvent.ToolFinishedEvent(
                turnId,
                Instant.now(),
                call.id(),
                call.toolName(),
                result.error(),
                result.awaitUser(),
                replacementResult.replacement()
        ));
        return message;
    }

    private void applyToolResultBudget(String turnId, List<ChatMessage> messages, List<PersistenceAction> actions,
                                       List<ToolResultMessage> toolResultMessages) {
        ToolResultBudgetResult budgetResult = contextManager.applyToolResultBudget(List.copyOf(toolResultMessages));
        applyBudgetedToolResults(messages, actions, toolResultMessages, budgetResult.results());
        toolResultMessages.clear();
        toolResultMessages.addAll(budgetResult.results());
        if (!budgetResult.replacements().isEmpty()) {
            publishEvent(new ToolResultsBudgetedEvent(turnId, Instant.now(), budgetResult.replacements()));
        }
    }

    private void applyBudgetedToolResults(List<ChatMessage> messages, List<PersistenceAction> actions,
                                          List<ToolResultMessage> originalResults,
                                          List<ToolResultMessage> budgetedResults) {
        if (originalResults.size() != budgetedResults.size()) {
            throw new IllegalStateException("budgeted tool result count must match original result count");
        }
        for (int index = 0; index < originalResults.size(); index++) {
            ToolResultMessage original = originalResults.get(index);
            ToolResultMessage budgeted = budgetedResults.get(index);
            if (original.equals(budgeted)) {
                continue;
            }
            replaceToolResultMessage(messages, original, budgeted);
            replaceToolResultAction(actions, original, budgeted);
        }
    }

    private void replaceToolResultMessage(List<ChatMessage> messages, ToolResultMessage original,
                                          ToolResultMessage replacement) {
        for (int index = 0; index < messages.size(); index++) {
            ChatMessage message = messages.get(index);
            if (message instanceof ToolResultMessage toolResult && sameToolResultSlot(toolResult, original)) {
                messages.set(index, replacement);
                return;
            }
        }
    }

    private void replaceToolResultAction(List<PersistenceAction> actions, ToolResultMessage original,
                                         ToolResultMessage replacement) {
        for (int actionIndex = 0; actionIndex < actions.size(); actionIndex++) {
            PersistenceAction action = actions.get(actionIndex);
            if (action instanceof PersistenceAction.AppendMessagesAction appendMessagesAction) {
                List<ChatMessage> actionMessages = appendMessagesAction.messages();
                for (int messageIndex = 0; messageIndex < actionMessages.size(); messageIndex++) {
                    ChatMessage message = actionMessages.get(messageIndex);
                    if (message instanceof ToolResultMessage toolResult && sameToolResultSlot(toolResult, original)) {
                        List<ChatMessage> replacementMessages = new ArrayList<>(actionMessages);
                        replacementMessages.set(messageIndex, replacement);
                        actions.set(actionIndex, new PersistenceAction.AppendMessagesAction(replacementMessages));
                        return;
                    }
                }
            }
        }
    }

    private boolean sameToolResultSlot(ToolResultMessage candidate, ToolResultMessage expected) {
        return candidate.toolUseId().equals(expected.toolUseId())
                && candidate.toolName().equals(expected.toolName())
                && candidate.content().equals(expected.content())
                && candidate.error() == expected.error();
    }

    private String awaitUserQuestion(String toolUseId, List<ToolResultMessage> toolResultMessages) {
        return toolResultMessages.stream()
                .filter(message -> message.toolUseId().equals(toolUseId))
                .findFirst()
                .map(ToolResultMessage::content)
                .orElse("");
    }

    /**
     * 将 {@link ToolCallsStep} 中伴随工具调用返回的文本和 thinking blocks 投影为消息。
     *
     * <p>有些 provider 会在同一次响应里同时返回说明文本、进度文本和工具调用。
     * 这个方法会在追加 tool_use 消息之前先保留这些文本；如果文本语义是 progress，
     * 还会追加续跑提示，避免模型只汇报进度就停住。</p>
     */
    private void appendToolCallsStepProjection(String turnId, List<ChatMessage> messages,
                                               List<PersistenceAction> actions, ToolCallsStep step) {
        step.content()
                .filter(content -> !content.isBlank())
                .ifPresent(content -> {
                    appendMessage(turnId, messages, actions, projectToolCallsContent(step, content));
                    if (step.contentKind() == minicode.core.step.ContentKind.PROGRESS) {
                        appendMessage(turnId, messages, actions, new UserMessage(PROGRESS_CONTINUATION_PROMPT));
                    }
                });

        if (!step.thinkingBlocks().isEmpty()) {
            appendMessage(turnId, messages, actions, new AssistantThinkingMessage(step.thinkingBlocks()));
        }
    }

    private ChatMessage projectToolCallsContent(ToolCallsStep step, String content) {
        return switch (step.contentKind()) {
            case PROGRESS -> new AssistantProgressMessage(content);
            case UNSPECIFIED -> new AssistantMessage(content);
        };
    }

    /**
     * 判断一次空文本 {@link AssistantStep} 是否属于可继续推进的 thinking 截断。
     *
     * <p>有些 provider 会在模型还处于 thinking/reasoning 阶段时因为
     * {@code max_tokens} 或 {@code pause_turn} 停止响应。此时模型还没有产出最终文本、
     * 进度文本或工具调用，但响应诊断里能看到 thinking 相关 block。遇到这种情况时，
     * {@link #runTurn(AgentTurnRequest)} 不应该直接走空响应 fallback，而是可以追加续跑提示，
     * 让模型继续产出下一步工具调用或最终回复。</p>
     *
     * @param step 模型返回的文本类 step
     * @return {@code true} 表示该 step 是 thinking 阶段被截断/暂停，可以尝试续跑
     */
    private boolean isRecoverableThinkingStop(AssistantStep step) {
        // 只有“没有正文但有诊断信息”的响应，才需要继续判断是否是 thinking 截断。
        if (!step.content().isBlank() || step.diagnostics().isEmpty()) {
            return false;
        }
        minicode.model.StepDiagnostics diagnostics = step.diagnostics().orElseThrow();
        String stopReason = diagnostics.stopReason().orElse("");
        // 只把 pause_turn 和 max_tokens 当作可恢复停止；其他停止原因交给后续空响应逻辑处理。
        if (!"pause_turn".equals(stopReason) && !"max_tokens".equals(stopReason)) {
            return false;
        }
        // 最后确认响应确实出现过 thinking block，避免把普通空响应误判成可续跑状态。
        return !step.thinkingBlocks().isEmpty()
                || diagnostics.blockTypes().contains("thinking")
                || diagnostics.ignoredBlockTypes().contains("thinking");
    }

    private String emptyResponseContinuationPrompt(boolean sawToolResultThisTurn, int toolErrorCount) {
        // 本轮工具有错误
        if (toolErrorCount > 0) {
            return EMPTY_RESPONSE_AFTER_TOOL_ERROR_CONTINUATION_PROMPT;
        }
        // 本轮没有工具错误，有工具结果
        if (sawToolResultThisTurn) {
            return EMPTY_RESPONSE_AFTER_TOOL_RESULT_CONTINUATION_PROMPT;
        }
        // 没有工具错误，没有工具结果
        return EMPTY_RESPONSE_CONTINUATION_PROMPT;
    }

    private String emptyFallbackReason(boolean sawToolResultThisTurn, int toolErrorCount) {
        if (toolErrorCount > 0) {
            return "empty_after_tool_error";
        }
        if (sawToolResultThisTurn) {
            return "empty_after_tool_result";
        }
        return "empty_response_retry_exhausted";
    }

    private String emptyFallbackMessage(boolean sawToolResultThisTurn, int toolErrorCount, AssistantStep step) {
        String diagnostics = formatDiagnostics(step); // 将诊断信息转为 string
        // 工具错误后空响应
        if (toolErrorCount > 0) {
            return "The model returned an empty response after recent tool results. Stopping this turn after "
                    + toolErrorCount + (toolErrorCount == 1 ? " tool error" : " tool errors") + "."
                    + diagnostics;
        }
        // 有工具结果，空响应
        if (sawToolResultThisTurn) {
            return "The model returned an empty response after recent tool results. Stopping this turn; retry or ask the model to continue from the tool output."
                    + diagnostics;
        }
        // 普通空响应
        return EMPTY_RESPONSE_MESSAGE + diagnostics;
    }

    private String emptyFallbackDiagnostics(AssistantStep step, boolean sawToolResultThisTurn, int toolErrorCount) {
        List<String> parts = new ArrayList<>();
        parts.add("reason=" + emptyFallbackReason(sawToolResultThisTurn, toolErrorCount));
        parts.add("sawToolResultThisTurn=" + sawToolResultThisTurn);
        parts.add("toolErrorCount=" + toolErrorCount);
        step.diagnostics().flatMap(minicode.model.StepDiagnostics::stopReason)
                .ifPresent(stopReason -> parts.add("stopReason=" + stopReason));
        step.diagnostics().ifPresent(diagnostics -> {
            if (!diagnostics.blockTypes().isEmpty()) {
                parts.add("blocks=" + String.join(",", diagnostics.blockTypes()));
            }
            if (!diagnostics.ignoredBlockTypes().isEmpty()) {
                parts.add("ignored=" + String.join(",", diagnostics.ignoredBlockTypes()));
            }
        });
        return String.join("; ", parts);
    }

    private String formatDiagnostics(AssistantStep step) {
        if (step.diagnostics().isEmpty()) {
            return "";
        }
        minicode.model.StepDiagnostics diagnostics = step.diagnostics().orElseThrow();
        List<String> parts = new ArrayList<>();
        diagnostics.stopReason().ifPresent(stopReason -> parts.add("stopReason=" + stopReason));
        if (!diagnostics.blockTypes().isEmpty()) {
            parts.add("blocks=" + String.join(",", diagnostics.blockTypes()));
        }
        if (!diagnostics.ignoredBlockTypes().isEmpty()) {
            parts.add("ignored=" + String.join(",", diagnostics.ignoredBlockTypes()));
        }
        return parts.isEmpty() ? "" : " Diagnostics: " + String.join("; ", parts) + ".";
    }

    private ToolContext createToolContext(AgentTurnRequest request, String toolUseId) {
        return new ToolContext(request.cwd(), request.sessionId(), Optional.of(request.turnId()), Optional.of(toolUseId),
                request.cancellationToken());
    }

    private void publishEvent(AgentEvent event) {
        try {
            eventSink.onEvent(event);
        } catch (RuntimeException ignored) {
            // Sink failures are observational and must not interrupt core turn progression.
        }
    }

    private AgentTurnResult modelErrorResult(List<ChatMessage> messages, List<PersistenceAction> actions,
                                             String errorMessage, String causeClass) {
        return AgentTurnResult.modelError(
                List.copyOf(messages),
                new TurnPersistencePlan(actions),
                new ModelErrorDetails(new TurnError(
                        errorMessage,
                        TurnErrorSource.MODEL,
                        false,
                        Optional.empty(),
                        Optional.of(causeClass)
                ))
        );
    }

    private AgentTurnResult modelErrorResult(List<ChatMessage> messages, List<PersistenceAction> actions,
                                             ModelRequestException exception) {
        String message = exception.getMessage() == null || exception.getMessage().isBlank()
                ? "Model request failed"
                : exception.getMessage();
        Optional<String> diagnostics = enrichedModelErrorDiagnostics(exception, messages);
        return AgentTurnResult.modelError(
                List.copyOf(messages),
                new TurnPersistencePlan(actions),
                new ModelErrorDetails(new TurnError(
                        message,
                        TurnErrorSource.MODEL,
                        exception.retryable(),
                        diagnostics,
                        Optional.of(ModelRequestException.class.getName())
                ))
        );
    }

    private Optional<String> enrichedModelErrorDiagnostics(ModelRequestException exception, List<ChatMessage> messages) {
        List<String> parts = new ArrayList<>();
        exception.diagnostics().ifPresent(parts::add);
        exception.statusCode().ifPresent(statusCode -> {
            String existingDiagnostics = exception.diagnostics().orElse("");
            String normalized = existingDiagnostics.toLowerCase(java.util.Locale.ROOT).replace(" ", "");
            if (existingDiagnostics.isBlank()
                    || (!normalized.contains("statuscode=") && !normalized.contains("status="))) {
                parts.add("statusCode=" + statusCode);
            }
        });
        if (recentToolHistory(messages)) {
            parts.add("recentToolHistory=true");
        }
        return parts.isEmpty() ? Optional.empty() : Optional.of(String.join("; ", parts));
    }

    private boolean recentToolHistory(List<ChatMessage> messages) {
        int start = Math.max(0, messages.size() - 12);
        for (int index = start; index < messages.size(); index++) {
            ChatMessage message = messages.get(index);
            if (message instanceof AssistantToolCallMessage || message instanceof ToolResultMessage) {
                return true;
            }
        }
        return false;
    }

    private AgentTurnResult cancelledResult(String turnId, List<ChatMessage> messages, List<PersistenceAction> actions,
                                            TurnCancellation cancellation) {
        publishEvent(new AgentEvent.TurnCancelledEvent(turnId, Instant.now(), cancellation));
        return AgentTurnResult.cancelled(
                List.copyOf(messages),
                new TurnPersistencePlan(actions),
                new CancellationDetails(cancellation)
        );
    }
}
