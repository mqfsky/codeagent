package minicode.tui.render;

import minicode.tui.terminal.TerminalSize;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class TuiRenderer {
    private static final int CHROME_ROWS = 4;
    private static final int TOOL_PREVIEW_LINES = 7;

    public RenderFrame render(RenderState state, TerminalSize size) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(size, "size");
        int width = size.columns();
        List<String> transcriptLines = renderTranscriptLines(state, width);
        int height = size.rows();
        int transcriptRows = Math.max(1, height - CHROME_ROWS);
        int scrollOffset = Math.min(state.scrollOffset(), maxScrollOffset(transcriptLines, transcriptRows));
        ArrayList<String> lines = new ArrayList<>(height);
        lines.add(fit(headerText(width), width));
        lines.addAll(renderTranscript(transcriptLines, width, transcriptRows, scrollOffset));
        lines.add(fit("─".repeat(width), width));
        lines.add(fit(statusText(state, scrollOffset), width));
        lines.add(fit(inputText(state.input()), width));
        return new RenderFrame(width, height, lines, height, inputCursorColumn(state.input(), width));
    }

    /**
     * 返回当前终端尺寸下真正可滚动的行数，供输入层限制滚动状态。
     */
    public int maxScrollOffset(RenderState state, TerminalSize size) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(size, "size");
        int transcriptRows = Math.max(1, size.rows() - CHROME_ROWS);
        return maxScrollOffset(renderTranscriptLines(state, size.columns()), transcriptRows);
    }

    private static List<String> renderTranscriptLines(RenderState state, int width) {
        ArrayList<String> rendered = new ArrayList<>();
        for (TranscriptBlock block : state.transcript()) {
            for (String line : renderBlock(block)) {
                rendered.addAll(wrap(line, width));
            }
        }
        return rendered.isEmpty() ? List.of(blank(width)) : rendered;
    }

    private static List<String> renderBlock(TranscriptBlock block) {
        return switch (block.kind()) {
            case USER -> prefixed("❯ You › ", plainLines(block.text()), "        ");
            case USER_ANSWER -> prefixed("❯ Answer › ", plainLines(block.text()), "           ");
            case ASSISTANT -> prefixed("● CodeAgent › ", TerminalMarkdown.render(block.text()), "  ");
            case PROGRESS -> prefixed("· Working › ", plainLines(block.text()), "  ");
            case TOOL -> renderTool(block);
            case ASK_USER -> prefixed("? Question › ", plainLines(block.text()), "  ");
            case PERMISSION -> prefixed("! Permission › ", plainLines(block.text()), "  ");
            case DIAGNOSTIC -> prefixed("· ", plainLines(block.text()), "  ");
            case COMPACT -> prefixed("↻ Context › ", plainLines(block.text()), "  ");
            case AGENT_TASK -> prefixed("◎ Agent task › ", plainLines(block.text()), "  ");
        };
    }

    private static List<String> renderTool(TranscriptBlock block) {
        if (block.toolName().isEmpty() || block.toolStatus().isEmpty()) {
            return prefixed("◇ Tool › ", plainLines(block.text()), "  ");
        }
        String status = switch (block.toolStatus().orElseThrow()) {
            case RUNNING -> "…";
            case OK -> "✓";
            case ERROR -> "✗";
        };
        String heading = "◇ " + block.toolName().orElseThrow() + "  " + status;
        if (block.text().isBlank()) {
            return List.of(heading);
        }
        ArrayList<String> lines = new ArrayList<>();
        lines.add(heading);
        for (String line : boundedToolLines(block.text())) {
            lines.add("  │ " + line);
        }
        return List.copyOf(lines);
    }

    private static List<String> boundedToolLines(String text) {
        String[] lines = text.replace("\r", "").split("\n", -1);
        if (lines.length <= TOOL_PREVIEW_LINES) {
            return List.of(lines);
        }
        ArrayList<String> preview = new ArrayList<>();
        int visibleLines = Math.max(1, TOOL_PREVIEW_LINES - 1);
        for (int index = 0; index < Math.min(lines.length, visibleLines); index++) {
            preview.add(lines[index]);
        }
        preview.add("… " + (lines.length - visibleLines) + " more lines");
        return List.copyOf(preview);
    }

    private static List<String> plainLines(String text) {
        return List.of(text.replace("\r", "").split("\n", -1));
    }

    private static List<String> prefixed(String firstPrefix, List<String> content, String continuationPrefix) {
        ArrayList<String> result = new ArrayList<>();
        List<String> actualContent = content.isEmpty() ? List.of("") : content;
        result.add(firstPrefix + actualContent.getFirst());
        for (int index = 1; index < actualContent.size(); index++) {
            result.add(continuationPrefix + actualContent.get(index));
        }
        return List.copyOf(result);
    }

    private static List<String> renderTranscript(List<String> rendered, int width, int rows, int scrollOffset) {
        int endExclusive = Math.max(0, rendered.size() - scrollOffset);
        int startInclusive = Math.max(0, endExclusive - rows);
        ArrayList<String> viewport = new ArrayList<>(rendered.subList(startInclusive, endExclusive));
        while (viewport.size() < rows) {
            viewport.add(0, blank(width));
        }
        return viewport.stream().map(line -> fit(line, width)).toList();
    }

    private static int maxScrollOffset(List<String> rendered, int rows) {
        return Math.max(0, rendered.size() - rows);
    }

    private static List<String> wrap(String text, int width) {
        String value = Objects.requireNonNull(text, "text").replace("\r", "");
        if (value.isEmpty()) {
            return List.of("");
        }
        ArrayList<String> lines = new ArrayList<>();
        for (String rawLine : value.split("\n", -1)) {
            if (rawLine.isEmpty()) {
                lines.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            int lineWidth = 0;
            for (int index = 0; index < rawLine.length(); ) {
                int codePoint = rawLine.codePointAt(index);
                int charWidth = DisplayText.isWide(codePoint) ? 2 : 1;
                if (lineWidth > 0 && lineWidth + charWidth > width) {
                    lines.add(line.toString());
                    line.setLength(0);
                    lineWidth = 0;
                }
                if (charWidth <= width) {
                    line.appendCodePoint(codePoint);
                    lineWidth += charWidth;
                }
                index += Character.charCount(codePoint);
            }
            if (line.length() > 0) {
                lines.add(line.toString());
            }
        }
        return lines;
    }

    private static String fit(String text, int width) {
        StringBuilder value = new StringBuilder();
        int currentWidth = 0;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            int charWidth = DisplayText.isWide(codePoint) ? 2 : 1;
            if (currentWidth + charWidth > width) {
                break;
            }
            value.appendCodePoint(codePoint);
            currentWidth += charWidth;
            index += Character.charCount(codePoint);
        }
        return value + " ".repeat(width - currentWidth);
    }

    private static String headerText(int width) {
        String title = " CodeAgent ";
        return title + "─".repeat(Math.max(0, width - DisplayText.width(title)));
    }

    private static String statusText(RenderState state, int scrollOffset) {
        String base = state.status().text().orElse("Ready");
        StringBuilder text = new StringBuilder("● ").append(base);
        state.contextBadge().ifPresent(badge -> text.append("  ·  ").append(badge));
        if (scrollOffset > 0) {
            text.append("  ·  history ↑ ").append(scrollOffset);
        }
        return text.toString();
    }

    private static String inputText(InputState input) {
        String prompt = switch (input.mode()) {
            case NORMAL -> "› ";
            case BUSY -> "… ";
            case AWAITING_ASK_USER -> "answer › ";
            case PENDING_PERMISSION -> "allow › ";
            case PERMISSION_FEEDBACK -> "feedback › ";
        };
        return prompt + input.text();
    }

    private static int inputCursorColumn(InputState input, int width) {
        int promptLength = switch (input.mode()) {
            case NORMAL -> DisplayText.width("› ");
            case BUSY -> DisplayText.width("… ");
            case AWAITING_ASK_USER -> DisplayText.width("answer › ");
            case PENDING_PERMISSION -> DisplayText.width("allow › ");
            case PERMISSION_FEEDBACK -> DisplayText.width("feedback › ");
        };
        String visibleBeforeCursor = input.text().substring(0, Math.min(input.cursor(), input.text().length()));
        return Math.min(width, promptLength + DisplayText.width(visibleBeforeCursor) + 1);
    }

    private static String blank(int width) {
        return " ".repeat(width);
    }
}
