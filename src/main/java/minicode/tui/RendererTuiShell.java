package minicode.tui;

import minicode.agent.event.AgentTaskEvent;
import minicode.agent.task.SubAgentTaskManager;
import minicode.app.ApplicationServices;
import minicode.context.stats.ContextWarningLevel;
import minicode.core.event.AgentEvent;
import minicode.core.message.AssistantMessage;
import minicode.core.message.AssistantProgressMessage;
import minicode.core.message.AssistantToolCallMessage;
import minicode.core.message.ChatMessage;
import minicode.core.message.ContextSummaryMessage;
import minicode.core.message.SystemMessage;
import minicode.core.message.ToolResultMessage;
import minicode.core.message.UserMessage;
import minicode.core.turn.AgentTurnResult;
import minicode.core.turn.AgentTurnStopReason;
import minicode.permissions.model.PermissionChoice;
import minicode.permissions.model.PermissionDecision;
import minicode.permissions.model.PermissionPromptResult;
import minicode.permissions.model.PermissionRequest;
import minicode.session.model.SessionEvent;
import minicode.session.model.SessionEventType;
import minicode.session.plan.PersistenceAction;
import minicode.session.plan.TurnPersistencePlan;
import minicode.skills.SkillCatalogFormatter;
import minicode.tui.input.LineTuiInput;
import minicode.tui.input.TuiInput;
import minicode.tui.input.TuiInputEvent;
import minicode.tui.render.InputState;
import minicode.tui.render.RenderFrame;
import minicode.tui.render.RenderState;
import minicode.tui.render.StatusState;
import minicode.tui.render.TranscriptBlock;
import minicode.tui.render.TuiRenderer;
import minicode.tui.terminal.TerminalScreen;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class RendererTuiShell {
    private static final int PERMISSION_DETAIL_LINES = 18;
    private static final int PERMISSION_DETAIL_LINE_CHARS = 180;
    private static final int MOUSE_WHEEL_SCROLL_LINES = 3;

    private final ApplicationServices services;
    private final TuiInput input;
    private final TerminalScreen screen;
    private final TuiRenderer renderer;
    private final int maxSteps;
    private final Object lock = new Object();
    private final boolean realtimeEvents;
    private RenderState state;
    private Thread activeTurn;
    private PendingPermission pendingPermission;

    public RendererTuiShell(ApplicationServices services, LineInput input, TerminalScreen screen, int maxSteps) {
        this(services, new LineTuiInput(input), screen, maxSteps, null);
    }

    public RendererTuiShell(ApplicationServices services, LineInput input, TerminalScreen screen, int maxSteps,
                            RendererTuiBridge bridge) {
        this(services, new LineTuiInput(input), screen, maxSteps, bridge);
    }

    public RendererTuiShell(ApplicationServices services, TuiInput input, TerminalScreen screen, int maxSteps,
                            RendererTuiBridge bridge) {
        this.services = Objects.requireNonNull(services, "services");
        this.input = Objects.requireNonNull(input, "input");
        this.screen = Objects.requireNonNull(screen, "screen");
        this.renderer = new TuiRenderer();
        if (maxSteps < 1) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
        this.maxSteps = maxSteps;
        this.realtimeEvents = bridge != null;
        if (bridge != null) {
            bridge.attach(this);
        }
        this.state = RenderState.empty().withTranscript(projectSessionHistory());
        redraw();
        SubAgentTaskManager taskManager = services.subAgentTaskManager().orElse(null);
        if (taskManager != null) {
            taskManager.setNotificationListener(this::startNotificationTurnIfIdle);
        }
    }

    public void runOnce() {
        try {
            runEvent(input.readEvent());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public void runLoop() {
        try {
            while (true) {
                if (!runEvent(input.readEvent())) {
                    return;
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public void awaitIdle(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        Thread thread;
        synchronized (lock) {
            thread = activeTurn;
        }
        if (thread == null) {
            return;
        }
        try {
            thread.join(Math.max(1L, timeout.toMillis()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for renderer turn", exception);
        }
    }

    void onAgentEvent(AgentEvent event) {
        Objects.requireNonNull(event, "event");
        synchronized (lock) {
            switch (event) {
                case AgentEvent.AssistantMessageEvent assistantMessageEvent ->
                        projectMessage(assistantMessageEvent.message()).ifPresent(this::appendTranscriptLocked);
                case AgentEvent.ToolStartedEvent toolStartedEvent -> {
                    String summary = toolStartedEvent.toolName().equals("ask_user")
                            ? ""
                            : ToolInputSummarizer.summarize(toolStartedEvent.toolName(), toolStartedEvent.input());
                    appendTranscriptLocked(TranscriptBlock.toolStarted(
                            toolStartedEvent.toolUseId(), toolStartedEvent.toolName(), summary));
                    state = state.withStatus(StatusState.of("Running " + toolStartedEvent.toolName() + "..."))
                            .withInput(state.input().withMode(InputState.Mode.BUSY));
                }
                case AgentEvent.ToolFinishedEvent toolFinishedEvent -> {
                    String text = toolFinishedEvent.toolName().equals("ask_user")
                            ? ""
                            : toolFinishedEvent.replacement()
                            .map(record -> "<persisted-output originalChars=\"" + record.originalChars()
                                    + "\" storageRef=\"" + record.storageRef() + "\">\n"
                                    + record.preview() + "\n</persisted-output>")
                            .orElse("");
                    appendTranscriptLocked(TranscriptBlock.toolResult(toolFinishedEvent.toolUseId(),
                            toolFinishedEvent.toolName(), toolFinishedEvent.error(), text));
                    if (toolFinishedEvent.awaitUser()) {
                        state = state.withStatus(StatusState.of("Waiting for user answer..."))
                                .withInput(state.input().withMode(InputState.Mode.AWAITING_ASK_USER));
                    } else {
                        state = state.withStatus(StatusState.thinking())
                                .withInput(state.input().withMode(InputState.Mode.BUSY));
                    }
                }
                case AgentEvent.AwaitUserEvent awaitUserEvent -> {
                    appendTranscriptLocked(TranscriptBlock.askUser(awaitUserEvent.toolUseId(),
                            cleanAskUserQuestion(awaitUserEvent.question())));
                    state = state.withStatus(StatusState.of("Waiting for user answer..."))
                            .withInput(state.input().withMode(InputState.Mode.AWAITING_ASK_USER));
                }
                case AgentEvent.ContextStatsEvent contextStatsEvent -> state = state.withContextBadge(contextBadge(contextStatsEvent));
                case AgentEvent.TurnCancelledEvent cancelledEvent -> appendTranscriptLocked(TranscriptBlock.diagnostic(
                        "cancelled: source=" + cancelledEvent.cancellation().source()
                                + " phase=" + cancelledEvent.cancellation().phase()
                                + " reason=" + cancelledEvent.cancellation().reason()));
                case AgentEvent.AutoCompactEvent autoCompactEvent -> {
                    switch (autoCompactEvent.type()) {
                        case STARTED -> appendTranscriptLocked(TranscriptBlock.diagnostic("auto_compact: started"));
                        case COMPLETED -> autoCompactEvent.result()
                                .flatMap(minicode.context.compact.CompressionResult::boundary)
                                .ifPresent(boundary -> appendTranscriptLocked(TranscriptBlock.compact(
                                        "Auto compacted: " + boundary.metadata().tokensBefore()
                                                + " -> " + boundary.metadata().tokensAfter() + " tokens")));
                        case SKIPPED -> appendTranscriptLocked(TranscriptBlock.diagnostic("auto_compact: skipped "
                                + autoCompactEvent.reason().orElse("skipped")));
                        case FAILED -> appendTranscriptLocked(TranscriptBlock.diagnostic("auto_compact: failed "
                                + autoCompactEvent.reason().orElse("failed")));
                    }
                }
                case minicode.core.event.ToolResultsBudgetedEvent ignored -> {
                }
            }
            redrawLocked();
        }
    }

    /**
     * 展示子 Agent 的独立生命周期块，不改变主 turn 的输入模式、状态栏或权限弹窗。
     */
    void onAgentTaskEvent(AgentTaskEvent event) {
        Objects.requireNonNull(event, "event");
        synchronized (lock) {
            String taskId = event.taskId().orElse("sync");
            String role = event.agentType().name().toLowerCase(Locale.ROOT).replace('_', '-');
            String text = switch (event) {
                case AgentTaskEvent.StateChangedEvent state -> "[" + taskId + "] " + role
                        + " " + state.status().name().toLowerCase(Locale.ROOT);
                case AgentTaskEvent.ToolStartedEvent started -> "[" + taskId + "] " + role
                        + " tool " + started.toolName() + " started";
                case AgentTaskEvent.ToolFinishedEvent finished -> "[" + taskId + "] " + role
                        + " tool " + finished.toolName() + " "
                        + (finished.error() ? "failed" : "completed");
            };
            appendTranscriptLocked(TranscriptBlock.agentTask(taskId, text));
            redrawLocked();
        }
    }

    PermissionPromptResult requestPermission(PermissionRequest request) {
        Objects.requireNonNull(request, "request");
        CompletableFuture<PermissionPromptResult> future = new CompletableFuture<>();
        synchronized (lock) {
            pendingPermission = new PendingPermission(
                    request, future, Optional.empty(), state.input(), state.status());
            appendTranscriptLocked(TranscriptBlock.permissionAudit(request.requestId(),
                    permissionPendingText(request)));
            state = state.withStatus(StatusState.of("Waiting for approval..."))
                    .withInput(InputState.of(InputState.Mode.PENDING_PERMISSION, "", 0));
            redrawLocked();
        }
        try {
            return future.get(365, TimeUnit.DAYS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return PermissionPromptResult.deny(PermissionDecision.DENY_WITH_FEEDBACK,
                    "Permission prompt interrupted");
        } catch (Exception exception) {
            return PermissionPromptResult.deny(PermissionDecision.DENY_WITH_FEEDBACK,
                    "Permission prompt failed: " + exception.getMessage());
        }
    }

    private boolean runEvent(TuiInputEvent event) {
        Objects.requireNonNull(event, "event");
        return switch (event.kind()) {
            case EOF -> false;
            case PAGE_UP -> {
                scrollUp(pageScrollLines());
                yield true;
            }
            case PAGE_DOWN -> {
                scrollDown(pageScrollLines());
                yield true;
            }
            case SCROLL_UP -> {
                scrollUp(MOUSE_WHEEL_SCROLL_LINES);
                yield true;
            }
            case SCROLL_DOWN -> {
                scrollDown(MOUSE_WHEEL_SCROLL_LINES);
                yield true;
            }
            case CURSOR_LEFT -> {
                moveCursorLeft();
                yield true;
            }
            case CURSOR_RIGHT -> {
                moveCursorRight();
                yield true;
            }
            case CHARACTER -> {
                appendInputCharacter(event.character().orElseThrow());
                yield true;
            }
            case BACKSPACE -> {
                backspaceInputCharacter();
                yield true;
            }
            case SUBMIT -> runLine(submittedText(event));
        };
    }

    private String submittedText(TuiInputEvent event) {
        if (event.text().isPresent()) {
            return event.text().orElseThrow();
        }
        synchronized (lock) {
            return state.input().text();
        }
    }

    /**
     * 处理用户提交的一整行输入。
     *
     * <p>权限请求具有最高优先级：存在待处理权限时，本行会被解释为权限选项或拒绝反馈，
     * 并在得到最终结果后完成对应的 {@link CompletableFuture}，使等待授权的执行线程继续运行。
     * 没有待处理权限时，输入才会继续按照退出命令、内置命令或普通用户消息处理。</p>
     *
     * @param line 用户提交的输入；为 {@code null} 时表示输入结束
     * @return {@code true} 表示继续运行 TUI，{@code false} 表示结束交互循环
     */
    private boolean runLine(String line) {
        if (line == null) {
            return false;
        }

        PendingPermission permissionToResolve;
        PermissionPromptResult permissionResult;
        // pendingPermission 会被权限请求线程和 TUI 输入线程共同访问，读取快照时必须加锁。
        synchronized (lock) {
            permissionToResolve = pendingPermission;
        }

        // 存在待授权请求时，当前输入优先作为权限选项或拒绝原因，不再按普通命令处理。
        // 如果有权限选项 pendingPermission 不为 null
        if (permissionToResolve != null) {
            permissionResult = handlePermissionInput(line);
            // null 表示输入尚未形成最终决定，例如选项无效，或还需要用户继续填写拒绝原因。
            if (permissionResult != null) {
                // 完成 future，唤醒 requestPermission(...) 中等待授权结果的执行线程。
                permissionToResolve.future().complete(permissionResult);
            }
            // 权限交互期间始终保持 TUI 运行，等待本次或下一次权限输入。
            return true;
        }

        String trimmed = line.trim();
        if (trimmed.isBlank()) {
            return true;
        }
        if ("exit".equalsIgnoreCase(trimmed) || "quit".equalsIgnoreCase(trimmed)) {
            return false;
        }
        if (handleScrollCommand(trimmed)) {
            return true;
        }
        if ("/compact".equals(trimmed)) {
            runCompactCommand();
            return true;
        }
        if ("/memory".equals(trimmed)) {
            runMemoryCommand();
            return true;
        }
        if ("/init".equals(trimmed)) {
            runInitCommand();
            return true;
        }
        if ("/skill".equals(trimmed)) {
            runSkillCommand();
            return true;
        }
        boolean answerMode;
        synchronized (lock) {
            answerMode = state.input().mode() == InputState.Mode.AWAITING_ASK_USER;
        }
        startTurn(line, answerMode);
        return true;
    }

    private void appendInputCharacter(char value) {
        synchronized (lock) {
            InputState current = state.input();
            if (current.mode() == InputState.Mode.BUSY) {
                return;
            }
            String text = current.text();
            int cursor = current.cursor();
            String next = text.substring(0, cursor) + value + text.substring(cursor);
            state = state.withInput(InputState.of(current.mode(), next, cursor + 1));
            redrawLocked();
        }
    }

    private void backspaceInputCharacter() {
        synchronized (lock) {
            InputState current = state.input();
            if (current.mode() == InputState.Mode.BUSY || current.cursor() == 0) {
                return;
            }
            String text = current.text();
            int cursor = current.cursor();
            String next = text.substring(0, cursor - 1) + text.substring(cursor);
            state = state.withInput(InputState.of(current.mode(), next, cursor - 1));
            redrawLocked();
        }
    }

    private void moveCursorLeft() {
        synchronized (lock) {
            InputState current = state.input();
            if (current.mode() == InputState.Mode.BUSY || current.cursor() == 0) {
                return;
            }
            state = state.withInput(InputState.of(current.mode(), current.text(), current.cursor() - 1));
            redrawLocked();
        }
    }

    private void moveCursorRight() {
        synchronized (lock) {
            InputState current = state.input();
            if (current.mode() == InputState.Mode.BUSY || current.cursor() >= current.text().length()) {
                return;
            }
            state = state.withInput(InputState.of(current.mode(), current.text(), current.cursor() + 1));
            redrawLocked();
        }
    }

    private boolean handleScrollCommand(String trimmed) {
        synchronized (lock) {
            switch (trimmed.toLowerCase(Locale.ROOT)) {
                case "/scroll-up", "/pageup" -> {
                    scrollUpLocked(pageScrollLines());
                    return true;
                }
                case "/scroll-down", "/pagedown" -> {
                    scrollDownLocked(pageScrollLines());
                    return true;
                }
                case "/bottom" -> {
                    state = state.withScrollOffset(0);
                    redrawLocked();
                    return true;
                }
                default -> {
                    return false;
                }
            }
        }
    }

    private void scrollUp(int lines) {
        synchronized (lock) {
            scrollUpLocked(lines);
        }
    }

    private void scrollDown(int lines) {
        synchronized (lock) {
            scrollDownLocked(lines);
        }
    }

    private void scrollUpLocked(int lines) {
        int maxOffset = renderer.maxScrollOffset(state, screen.size());
        state = state.withScrollOffset(Math.min(maxOffset, state.scrollOffset() + Math.max(1, lines)));
        redrawLocked();
    }

    private void scrollDownLocked(int lines) {
        state = state.withScrollOffset(Math.max(0, state.scrollOffset() - Math.max(1, lines)));
        redrawLocked();
    }

    private int pageScrollLines() {
        return Math.max(1, screen.size().rows() - 4);
    }

    /**
     * 将用户输入解析为当前待处理权限请求的选择结果。
     *
     * <p>该方法同时处理两个阶段：第一阶段从权限选项中匹配用户输入；如果选中的拒绝项
     * 要求说明原因，则切换到反馈输入阶段（第二阶段）。只有形成最终的允许或拒绝决定时才返回
     * {@link PermissionPromptResult}，否则返回 {@code null}，让 TUI 继续等待输入。</p>
     *
     * @param line 用户在权限提示中提交的选项或拒绝原因
     * @return 最终权限结果；没有待处理请求、选项无效或仍需输入反馈时返回 {@code null}
     */
    private PermissionPromptResult handlePermissionInput(String line) {
        // 权限状态和界面状态可能被后台执行线程访问，整个解析及状态更新过程需要加锁。
        synchronized (lock) {
            // 权限请求可能已被其他流程清除，此时没有可处理的目标。
            if (pendingPermission == null) {
                return null;
            }
            PendingPermission pending = pendingPermission;

            // 第二阶段：已经选择了“拒绝并反馈”时，本次输入就是第二阶段的拒绝原因。
            if (pending.feedbackChoice().isPresent()) {
                PermissionChoice choice = pending.feedbackChoice().orElseThrow();
                String feedback = line == null || line.isBlank() ? "Permission denied by user" : line;
                PermissionPromptResult result = PermissionPromptResult.deny(choice.key(), choice.decision(), feedback);

                // 记录权限审计信息，并恢复权限弹窗前的界面状态。
                appendTranscriptLocked(TranscriptBlock.permissionAudit(pending.request().requestId(),
                        "denied " + choice.key() + " feedback=" + oneLine(feedback)));
                pendingPermission = null;
                restoreAfterPermissionLocked(pending);
                redrawLocked();
                return result;
            }

            // 第一阶段：允许用户通过序号、key 或选项文案选择一个权限决定。
            Optional<PermissionChoice> selected = selectChoice(pending.request(), line);
            if (selected.isEmpty()) {
                // 无法识别时保留当前权限请求，重新展示可选项并继续等待输入。
                // 重新生成提示
                appendTranscriptLocked(TranscriptBlock.permissionAudit(pending.request().requestId(),
                        "unknown choice " + line.trim() + "\n" + permissionChoicesText(pending.request())));
                // 重新渲染
                redrawLocked();
                return null;
            }
            PermissionChoice choice = selected.orElseThrow();

            // 需要拒绝原因的选项暂不产生最终结果，只把 TUI 切换到反馈输入阶段。
            if (choice.requiresFeedback()) {
                pendingPermission = new PendingPermission(
                        pending.request(), pending.future(), Optional.of(choice),
                        pending.resumeInput(), pending.resumeStatus());
                state = state.withStatus(StatusState.of("Permission feedback..."))
                        .withInput(InputState.of(InputState.Mode.PERMISSION_FEEDBACK, "", 0));
                redrawLocked();
                return null;
            }

            // 普通允许或拒绝选项可以立即转换成最终权限结果。
            PermissionPromptResult result = isAllow(choice.decision())
                    ? PermissionPromptResult.allow(choice.key(), choice.decision())
                    : PermissionPromptResult.deny(choice.key(), choice.decision(), null);

            // 权限交互结束：写入审计记录、清除待处理请求并恢复弹窗前的界面状态。
            // 父 Turn 发起的权限请求会恢复 BUSY；空闲后台任务发起的请求会恢复 NORMAL，
            // 从而允许任务结束后的通知自动唤醒父 Agent。
            appendTranscriptLocked(TranscriptBlock.permissionAudit(pending.request().requestId(),
                    (result.allowed() ? "allowed " : "denied ") + choice.key()));
            pendingPermission = null;
            restoreAfterPermissionLocked(pending);
            redrawLocked();
            return result;
        }
    }

    /** 恢复权限弹窗下方的最新界面状态，避免用过期 BUSY 快照覆盖已经结束的父 Turn。 */
    private void restoreAfterPermissionLocked(PendingPermission pending) {
        InputState currentInput = state.input();
        boolean permissionOverlay = currentInput.mode() == InputState.Mode.PENDING_PERMISSION
                || currentInput.mode() == InputState.Mode.PERMISSION_FEEDBACK;
        InputState nextInput = permissionOverlay ? pending.resumeInput() : currentInput;
        StatusState nextStatus = permissionOverlay ? pending.resumeStatus() : state.status();

        // 权限等待期间父 Turn 可能已经结束。此时旧快照中的 BUSY 不再有对应执行线程，
        // 必须回到可输入状态，否则后台结果通知也无法启动 continuation turn。
        if (activeTurn == null && nextInput.mode() == InputState.Mode.BUSY) {
            state = state.clearStatus().withInput(InputState.empty());
            return;
        }
        state = state.withStatus(nextStatus).withInput(nextInput);
    }

    private void startTurn(String line, boolean answerMode) {
        UserMessage userMessage = new UserMessage(line);
        Thread thread = new Thread(
                new UserTurnRunner(this, userMessage),
                "minicode-renderer-turn");
        synchronized (lock) {
            // activeTurn 非空即表示已经预留或正在执行，不能用 isAlive 判断尚未 start 的线程。
            if (activeTurn != null) {
                appendTranscriptLocked(TranscriptBlock.diagnostic("busy: wait for the current turn to finish"));
                redrawLocked();
                return;
            }
            appendTranscriptLocked(answerMode
                    ? TranscriptBlock.userAnswer(userMessage.content())
                    : TranscriptBlock.user(userMessage.content()));
            state = state.withInput(InputState.of(InputState.Mode.BUSY, "", 0))
                    .withStatus(StatusState.thinking());
            activeTurn = thread;
            redrawLocked();
        }
        thread.start();
    }

    private void runUserTurnInBackground(UserMessage userMessage) {
        try {
            List<ChatMessage> history = services.sessionMessages();
            services.sessionPersistenceRunner().apply(new TurnPersistencePlan(
                    List.of(new PersistenceAction.AppendMessagesAction(List.of(userMessage)))
            ));
            List<ChatMessage> turnMessages = new ArrayList<>(history);
            turnMessages.add(userMessage);
            runTurnInBackground(turnMessages);
        } catch (RuntimeException exception) {
            synchronized (lock) {
                if (Thread.currentThread() == activeTurn) {
                    activeTurn = null;
                }
                state = state.clearStatus().withInput(InputState.empty());
                appendTranscriptLocked(TranscriptBlock.diagnostic(
                        "turn_start_failed: " + Objects.toString(exception.getMessage(), "unknown error")));
                redrawLocked();
            }
            startNotificationTurnIfIdle();
        }
    }

    private void runTurnInBackground(List<ChatMessage> turnMessages) {
        AgentTurnResult result = null;
        try {
            // 进入 agent loop
            result = services.runTurn(services.turnRequest(List.copyOf(turnMessages), maxSteps));
            // 持久化 session
            services.sessionPersistenceRunner().apply(result.persistencePlan());
            // 更新 UI
            synchronized (lock) {
                if (!realtimeEvents) {
                    state = state.appendTranscript(projectPersistencePlan(result.persistencePlan()));
                }
                appendStopReasonLocked(result);
                redrawLocked();
            }
        } finally {
            synchronized (lock) {
                // 一轮对话结束后更新
                if (Thread.currentThread() == activeTurn) {
                    activeTurn = null;
                }
                if (result == null) {
                    state = state.clearStatus().withInput(InputState.empty());
                    redrawLocked();
                }
            }
            // 当前父 Turn 结束时如果后台任务刚好完成，立即自动启动一次结果汇总。
            startNotificationTurnIfIdle();
        }
    }

    /** 父 Agent 空闲且存在子任务通知时，无需用户输入即可继续一轮。 */
    private void startNotificationTurnIfIdle() {
        SubAgentTaskManager taskManager = services.subAgentTaskManager().orElse(null);
        if (taskManager == null || !taskManager.hasNotifications()) {
            return;
        }

        Thread thread;
        synchronized (lock) {
            InputState inputState = state.input();
            boolean interactive = pendingPermission != null
                    || inputState.mode() != InputState.Mode.NORMAL
                    || !inputState.text().isBlank();
            if (activeTurn != null || interactive || !taskManager.hasNotifications()) {
                return;
            }
            state = state.withInput(InputState.of(InputState.Mode.BUSY, "", 0))
                    .withStatus(StatusState.thinking());
            redrawLocked();
            thread = new Thread(
                    this::runNotificationTurnInBackground,
                    "minicode-renderer-agent-notification");
            activeTurn = thread;
        }
        thread.start();
    }

    private void runNotificationTurnInBackground() {
        runTurnInBackground(services.sessionMessages());
    }

    private void runCompactCommand() {
        appendDiagnostic("compact: started");
        minicode.context.compact.ManualCompactResult result = services.manualCompact();
        switch (result.status()) {
            case COMPACTED -> {
                var metadata = result.boundary().orElseThrow().metadata();
                appendDiagnostic("compact: ok compressed=" + metadata.messagesCompressed()
                        + " tokens=" + metadata.tokensBefore() + "->" + metadata.tokensAfter());
            }
            case SKIPPED -> appendDiagnostic("compact: skipped " + result.reason().orElse("nothing to compact"));
            case FAILED -> appendDiagnostic("compact: failed " + result.reason().orElse("summary generation failed"));
        }
    }

    private void runMemoryCommand() {
        String report = services.memorySnapshot().renderReport(services.cwd());
        synchronized (lock) {
            appendTranscriptLocked(TranscriptBlock.assistant(report));
            redrawLocked();
        }
    }

    private void runInitCommand() {
        String report = services.initializeProject();
        synchronized (lock) {
            appendTranscriptLocked(TranscriptBlock.assistant(report));
            redrawLocked();
        }
    }

    private void runSkillCommand() {
        String report = SkillCatalogFormatter.render(services.skillRegistry().summaries());
        synchronized (lock) {
            appendTranscriptLocked(TranscriptBlock.assistant(report));
            redrawLocked();
        }
    }

    private void appendDiagnostic(String text) {
        synchronized (lock) {
            appendTranscriptLocked(TranscriptBlock.diagnostic(text));
            redrawLocked();
        }
    }

    private void appendStopReasonLocked(AgentTurnResult result) {
        AgentTurnStopReason stopReason = result.stopReason();
        if (stopReason == AgentTurnStopReason.AWAIT_USER) {
            state = state.withStatus(StatusState.of("Waiting for user answer..."))
                    .withInput(InputState.of(InputState.Mode.AWAITING_ASK_USER, "", 0));
            return;
        }
        state = state.clearStatus().withInput(InputState.empty());
        if (stopReason == AgentTurnStopReason.FINAL) {
            return;
        }
        appendTranscriptLocked(TranscriptBlock.diagnostic("turn_stop: " + stopReason));
        switch (stopReason) {
            case MAX_STEPS -> appendTranscriptLocked(TranscriptBlock.diagnostic(
                    "max_steps: reached max steps. Type \"continue\" or \"继续\" to keep going from the current context."));
            case MODEL_ERROR -> appendTranscriptLocked(TranscriptBlock.diagnostic("model_error: "
                    + result.stopDetails().orElseThrow()));
            case CANCELLED -> appendTranscriptLocked(TranscriptBlock.diagnostic("cancelled: "
                    + result.stopDetails().orElseThrow()));
            case EMPTY_RESPONSE_FALLBACK -> appendTranscriptLocked(TranscriptBlock.diagnostic("empty_response_fallback"));
            case FINAL, AWAIT_USER -> {
            }
        }
    }

    private List<TranscriptBlock> projectSessionHistory() {
        List<TranscriptBlock> blocks = new ArrayList<>();
        for (SessionEvent event : services.sessionStore().readAll(services.sessionId(), services.cwd().toString())) {
            if (event.type() == SessionEventType.COMPACT_BOUNDARY) {
                blocks.add(TranscriptBlock.compact(event.compactMetadata()
                        .map(metadata -> "Context compacted: " + metadata.tokensBefore()
                                + " -> " + metadata.tokensAfter() + " tokens")
                        .orElse("Context compacted")));
                continue;
            }
            event.message().flatMap(RendererTuiShell::projectMessage).ifPresent(blocks::add);
        }
        return List.copyOf(blocks);
    }

    private static List<TranscriptBlock> projectPersistencePlan(TurnPersistencePlan plan) {
        ArrayList<TranscriptBlock> blocks = new ArrayList<>();
        for (PersistenceAction action : plan.actions()) {
            if (action instanceof PersistenceAction.AppendMessagesAction appendMessagesAction) {
                for (ChatMessage message : appendMessagesAction.messages()) {
                    projectMessage(message).ifPresent(blocks::add);
                }
            }
        }
        return blocks;
    }

    private static Optional<TranscriptBlock> projectMessage(ChatMessage message) {
        return switch (message) {
            case UserMessage userMessage -> Optional.of(TranscriptBlock.user(userMessage.content()));
            case AssistantMessage assistantMessage -> Optional.of(TranscriptBlock.assistant(assistantMessage.content()));
            case AssistantProgressMessage progressMessage -> Optional.of(TranscriptBlock.progress(progressMessage.content()));
            case AssistantToolCallMessage toolCallMessage -> Optional.of(TranscriptBlock.toolStarted(
                    toolCallMessage.toolUseId(), toolCallMessage.toolName(),
                    toolCallMessage.toolName().equals("ask_user")
                            ? ""
                            : ToolInputSummarizer.summarize(toolCallMessage.toolName(), toolCallMessage.input())));
            case ToolResultMessage toolResultMessage -> Optional.of(TranscriptBlock.toolResult(
                    toolResultMessage.toolUseId(), toolResultMessage.toolName(),
                    toolResultMessage.error(), toolResultMessage.toolName().equals("ask_user")
                            ? ""
                            : toolResultMessage.content()));
            case ContextSummaryMessage summary -> Optional.of(TranscriptBlock.compact(summary.content()));
            case SystemMessage ignored -> Optional.empty();
            default -> Optional.empty();
        };
    }

    private void appendTranscriptLocked(TranscriptBlock block) {
        state = state.appendTranscript(block);
    }

    private void redraw() {
        synchronized (lock) {
            redrawLocked();
        }
    }

    private void redrawLocked() {
        RenderFrame frame = renderer.render(state, screen.size());
        screen.redraw(frame);
    }

    private static Optional<PermissionChoice> selectChoice(PermissionRequest request, String line) {
        String normalized = normalize(line);
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        try {
            // 数字匹配
            int choiceNumber = Integer.parseInt(normalized);
            if (choiceNumber >= 1 && choiceNumber <= request.choices().size()) {
                // 返回用户的选项
                return Optional.of(request.choices().get(choiceNumber - 1));
            }
        } catch (NumberFormatException ignored) {
            // Continue with key/label matching.
        }
        // key 或 label 匹配
        return request.choices().stream()
                .filter(choice -> normalize(choice.key()).equals(normalized)
                        || normalize(choice.label()).equals(normalized))
                .findFirst();
    }

    private static String normalize(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length() > 2) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static boolean isAllow(PermissionDecision decision) {
        return decision == PermissionDecision.ALLOW_ONCE
                || decision == PermissionDecision.ALLOW_TURN
                || decision == PermissionDecision.ALLOW_ALWAYS;
    }

    private static String permissionPendingText(PermissionRequest request) {
        StringBuilder builder = new StringBuilder();
        builder.append("pending ").append(request.details().title());
        String body = oneLine(request.details().body());
        if (!body.isBlank()) {
            builder.append('\n').append(truncate(body, PERMISSION_DETAIL_LINE_CHARS));
        }
        appendPermissionDetails(builder, request.details().facts());
        builder.append('\n').append(permissionChoicesText(request));
        return builder.toString();
    }

    private static void appendPermissionDetails(StringBuilder builder, List<String> facts) {
        int appended = 0;
        for (String fact : facts) {
            if (appended >= PERMISSION_DETAIL_LINES) {
                int remaining = facts.size() - appended;
                builder.append('\n').append("... truncated permission details ").append(remaining).append(" lines");
                return;
            }
            String text = sanitizePermissionDetail(fact);
            if (!text.isBlank()) {
                builder.append('\n').append(truncate(text, PERMISSION_DETAIL_LINE_CHARS));
                appended++;
            }
        }
    }

    private static String permissionChoicesText(PermissionRequest request) {
        StringBuilder builder = new StringBuilder("choices:");
        List<PermissionChoice> choices = request.choices();
        for (int index = 0; index < choices.size(); index++) {
            PermissionChoice choice = choices.get(index);
            builder.append('\n')
                    .append('[').append(index + 1).append("] ")
                    .append(choice.key())
                    .append(" - ")
                    .append(choice.label());
        }
        return builder.toString();
    }

    private static String sanitizePermissionDetail(String value) {
        return value == null ? "" : value.replace("\r", "").stripTrailing();
    }

    private static String contextBadge(AgentEvent.ContextStatsEvent event) {
        double percent = event.stats().utilization() * 100.0d;
        return "context " + Math.round(percent) + "% "
                + warningLevelLabel(event.stats().warningLevel()) + " "
                + compactTokenCount(event.stats().accounting().totalTokens()) + "/"
                + compactTokenCount(event.stats().effectiveInput()) + " "
                + accountingSourceLabel(event.stats().accounting().source());
    }

    private static String warningLevelLabel(ContextWarningLevel level) {
        return switch (level) {
            case OK, NORMAL -> "normal";
            case WARNING -> "warning";
            case CRITICAL -> "critical";
            case BLOCKED -> "blocked";
        };
    }

    private static String accountingSourceLabel(minicode.context.accounting.TokenAccountingSource source) {
        return switch (source) {
            case PROVIDER_USAGE -> "provider";
            case PROVIDER_USAGE_WITH_ESTIMATE -> "provider+estimate";
            case ESTIMATE_ONLY -> "estimate";
        };
    }

    private static String compactTokenCount(long tokens) {
        if (tokens >= 1_000_000L) {
            double millions = tokens / 1_000_000.0d;
            return String.format(Locale.ROOT, "%.1fM", millions);
        }
        if (tokens >= 1_000L) {
            long thousands = Math.round(tokens / 1_000.0d);
            return thousands + "k";
        }
        return Long.toString(tokens);
    }

    private static String oneLine(String value) {
        String collapsed = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (collapsed.length() <= 180) {
            return collapsed;
        }
        return collapsed.substring(0, 180) + "...";
    }

    private static String truncate(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        if (maxChars <= 3) {
            return value.substring(0, maxChars);
        }
        return value.substring(0, maxChars - 3) + "...";
    }

    private static String cleanAskUserQuestion(String question) {
        String value = Objects.requireNonNull(question, "question").trim();
        String prefix = "Question for user:";
        if (value.startsWith(prefix)) {
            return value.substring(prefix.length()).trim();
        }
        return value;
    }

    /**
     * Renderer TUI 中等待用户选择的权限请求。
     *
     * @param request 待用户确认的权限请求
     * @param future 等待用户权限选择完成的 future
     * @param feedbackChoice 当前是否处于需要反馈的拒绝选项
     * @param resumeInput 权限交互结束后恢复的输入状态
     * @param resumeStatus 权限交互结束后恢复的状态栏
     */
    private static final class UserTurnRunner implements Runnable {
        private final RendererTuiShell shell;
        private final UserMessage userMessage;

        private UserTurnRunner(RendererTuiShell shell, UserMessage userMessage) {
            this.shell = Objects.requireNonNull(shell, "shell");
            this.userMessage = Objects.requireNonNull(userMessage, "userMessage");
        }

        @Override
        public void run() {
            shell.runUserTurnInBackground(userMessage);
        }
    }

    private record PendingPermission(PermissionRequest request, CompletableFuture<PermissionPromptResult> future,
                                     Optional<PermissionChoice> feedbackChoice,
                                     InputState resumeInput,
                                     StatusState resumeStatus) {
        private PendingPermission {
            request = Objects.requireNonNull(request, "request");
            future = Objects.requireNonNull(future, "future");
            feedbackChoice = Objects.requireNonNull(feedbackChoice, "feedbackChoice");
            resumeInput = Objects.requireNonNull(resumeInput, "resumeInput");
            resumeStatus = Objects.requireNonNull(resumeStatus, "resumeStatus");
        }
    }
}
