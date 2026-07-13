package minicode.tui.terminal;

import minicode.tui.render.RenderFrame;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.io.PrintWriter;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

public final class JLineTerminalScreen implements TerminalScreen {
    private static final String USER_PREFIX = "❯ ";
    private static final String USER_LABEL = "You";
    private static final String USER_LINE_PREFIX = USER_PREFIX + USER_LABEL + " › ";
    private static final AttributedStyle USER_LABEL_STYLE =
            AttributedStyle.DEFAULT.foreground(0, 175, 95);
    private static final String ASSISTANT_PREFIX = "● ";
    private static final String ASSISTANT_LABEL = "CodeAgent";
    private static final String ASSISTANT_LINE_PREFIX = ASSISTANT_PREFIX + ASSISTANT_LABEL + " › ";
    private static final AttributedStyle ASSISTANT_LABEL_STYLE =
            AttributedStyle.DEFAULT.foreground(255, 135, 0);
    private static final String ENTER_ALTERNATE_SCREEN = "\u001B[?1049h";
    private static final String EXIT_ALTERNATE_SCREEN = "\u001B[?1049l";
    private static final String ENABLE_ALTERNATE_SCROLL = "\u001B[?1007h";
    private static final String DISABLE_ALTERNATE_SCROLL = "\u001B[?1007l";
    private static final String ENABLE_MOUSE_TRACKING = "\u001B[?1000h";
    private static final String ENABLE_SGR_MOUSE = "\u001B[?1006h";
    private static final String DISABLE_ALL_MOUSE_MODES = "\u001B[?1000l"
            + "\u001B[?1002l"
            + "\u001B[?1003l"
            + "\u001B[?1005l"
            + "\u001B[?1006l"
            + "\u001B[?1015l";
    private static final String CURSOR_HOME = "\u001B[H";
    private static final String CLEAR_SCREEN = "\u001B[2J";
    private static final String SHOW_CURSOR = "\u001B[?25h";

    private final Terminal terminal;
    private final PrintWriter writer;
    private final Attributes originalAttributes;
    private final IntConsumer exitHandler;
    private final Terminal.SignalHandler previousInterruptHandler;
    private final Thread shutdownHook;
    private final boolean shutdownHookRegistered;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean interruptHandlerRestored = new AtomicBoolean(false);

    public JLineTerminalScreen(Terminal terminal) {
        this(terminal, System::exit);
    }

    JLineTerminalScreen(Terminal terminal, IntConsumer exitHandler) {
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        this.writer = terminal.writer();
        // JLineTuiInput 会在 Screen 创建后进入 raw mode；这里提前保存原始属性，确保退出时能完整恢复。
        this.originalAttributes = new Attributes(terminal.getAttributes());
        this.exitHandler = Objects.requireNonNull(exitHandler, "exitHandler");
        this.shutdownHook = new Thread(this::restoreOnShutdown, "codeagent-terminal-restore");
        // 不依赖 JVM shutdown hook 的执行顺序：Ctrl+C 到达时先同步恢复终端，再结束进程。
        this.previousInterruptHandler = terminal.handle(Terminal.Signal.INT, this::handleInterrupt);
        this.shutdownHookRegistered = registerShutdownHook(shutdownHook);
        writer.print(ENTER_ALTERNATE_SCREEN);
        writer.print(ENABLE_ALTERNATE_SCROLL);
        writer.print(ENABLE_MOUSE_TRACKING);
        writer.print(ENABLE_SGR_MOUSE);
        writer.print(SHOW_CURSOR);
        writer.print(CLEAR_SCREEN);
        writer.print(CURSOR_HOME);
        writer.flush();
    }

    @Override
    public TerminalSize size() {
        return new TerminalSize(Math.max(1, terminal.getWidth()), Math.max(1, terminal.getHeight()));
    }

    @Override
    public void redraw(RenderFrame frame) {
        Objects.requireNonNull(frame, "frame");
        writer.print(SHOW_CURSOR);
        writer.print(CURSOR_HOME);
        writer.print(CLEAR_SCREEN);
        writer.print(CURSOR_HOME);
        StringJoiner rendered = new StringJoiner("\r\n");
        for (String line : frame.lines()) {
            rendered.add(styleLine(line).toAnsi(terminal));
        }
        writer.print(rendered);
        if (frame.cursorRow() > 0 && frame.cursorColumn() > 0) {
            writer.print("\u001B[" + frame.cursorRow() + ";" + frame.cursorColumn() + "H");
        }
        writer.flush();
    }

    /**
     * 只高亮用户和助手回答开头的角色标签；返回值的可见文本与原始行完全一致。
     */
    static AttributedString styleLine(String line) {
        String value = Objects.requireNonNull(line, "line");
        if (value.startsWith(USER_LINE_PREFIX)) {
            return styleLabel(value, USER_PREFIX, USER_LABEL, USER_LABEL_STYLE);
        }
        if (value.startsWith(ASSISTANT_LINE_PREFIX)) {
            return styleLabel(value, ASSISTANT_PREFIX, ASSISTANT_LABEL, ASSISTANT_LABEL_STYLE);
        }
        return new AttributedString(value);
    }

    private static AttributedString styleLabel(String line, String prefix, String label, AttributedStyle style) {
        int labelStart = prefix.length();
        int labelEnd = labelStart + label.length();
        return new AttributedStringBuilder(line.length())
                .append(line, 0, labelStart)
                .styled(style, label)
                .append(line, labelEnd, line.length())
                .toAttributedString();
    }

    @Override
    public void close() {
        restoreTerminal();
        restoreInterruptHandler();
        removeShutdownHook();
    }

    private void handleInterrupt(Terminal.Signal signal) {
        restoreTerminal();
        // 128 + SIGINT(2) 是命令行程序被 Ctrl+C 终止时的标准退出码。
        exitHandler.accept(130);
    }

    /**
     * JVM 正常关闭、收到 Ctrl+C 或执行 System.exit 时使用的兜底清理入口。
     */
    void restoreOnShutdown() {
        restoreTerminal();
    }

    private void restoreTerminal() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        // 有些终端会在退出 alternate screen 时恢复私有模式，因此切屏前后各关闭一次鼠标追踪。
        writer.print(DISABLE_ALL_MOUSE_MODES);
        writer.print(DISABLE_ALTERNATE_SCROLL);
        writer.print(SHOW_CURSOR);
        writer.print(EXIT_ALTERNATE_SCREEN);
        writer.print(DISABLE_ALL_MOUSE_MODES);
        writer.print(DISABLE_ALTERNATE_SCROLL);
        writer.print(SHOW_CURSOR);
        writer.flush();

        // 恢复 ICANON、ECHO 等进入 TUI 前的终端属性，避免异常退出后 shell 仍停留在 raw mode。
        try {
            terminal.setAttributes(new Attributes(originalAttributes));
        } catch (RuntimeException ignored) {
            // 终端可能已经在关闭；控制序列已经尽力输出，属性恢复也保持 best-effort。
        }
    }

    private static boolean registerShutdownHook(Thread hook) {
        try {
            Runtime.getRuntime().addShutdownHook(hook);
            return true;
        } catch (IllegalStateException | SecurityException ignored) {
            return false;
        }
    }

    private void removeShutdownHook() {
        if (!shutdownHookRegistered) {
            return;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException | SecurityException ignored) {
            // JVM 已经进入 shutdown 阶段时不能移除 hook；hook 内的原子关闭保证重复调用安全。
        }
    }

    private void restoreInterruptHandler() {
        if (!interruptHandlerRestored.compareAndSet(false, true)) {
            return;
        }
        try {
            terminal.handle(Terminal.Signal.INT, previousInterruptHandler);
        } catch (RuntimeException ignored) {
            // 终端可能已经关闭；进程退出路径无需再恢复旧 handler。
        }
    }
}
