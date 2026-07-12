package minicode.tui.terminal;

import minicode.tui.render.RenderFrame;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.io.PrintWriter;
import java.util.Objects;
import java.util.StringJoiner;

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
    private static final String DISABLE_MOUSE_TRACKING = "\u001B[?1000l";
    private static final String ENABLE_SGR_MOUSE = "\u001B[?1006h";
    private static final String DISABLE_SGR_MOUSE = "\u001B[?1006l";
    private static final String CURSOR_HOME = "\u001B[H";
    private static final String CLEAR_SCREEN = "\u001B[2J";
    private static final String SHOW_CURSOR = "\u001B[?25h";

    private final Terminal terminal;
    private final PrintWriter writer;
    private boolean closed;

    public JLineTerminalScreen(Terminal terminal) {
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        this.writer = terminal.writer();
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
        if (closed) {
            return;
        }
        closed = true;
        writer.print(DISABLE_SGR_MOUSE);
        writer.print(DISABLE_MOUSE_TRACKING);
        writer.print(DISABLE_ALTERNATE_SCROLL);
        writer.print(SHOW_CURSOR);
        writer.print(EXIT_ALTERNATE_SCREEN);
        writer.flush();
    }
}
