package minicode.tui.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把模型常用的 Markdown 转成适合固定宽度终端显示的轻量纯文本。
 *
 * <p>这里有意不实现完整 Markdown 规范。TUI 只需要消除标题、代码围栏和表格分隔符等
 * 最影响阅读的原始标记，同时保证输出仍然可以直接选择和复制。</p>
 */
final class TerminalMarkdown {
    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}#{1,6}\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.+)$");
    private static final Pattern NUMBERED = Pattern.compile("^(\\s*)(\\d+)[.)]\\s+(.+)$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile(
            "^\\s*\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern LINK = Pattern.compile("!?\\[([^]]+)]\\(([^)]+)\\)");

    private TerminalMarkdown() {
    }

    static List<String> render(String markdown) {
        String value = Objects.requireNonNull(markdown, "markdown").replace("\r", "");
        if (value.isEmpty()) {
            return List.of("");
        }
        ArrayList<String> lines = new ArrayList<>();
        boolean inCodeBlock = false;
        String fence = "";
        for (String rawLine : value.split("\n", -1)) {
            String stripped = rawLine.stripLeading();
            if (!inCodeBlock && isFenceStart(stripped)) {
                inCodeBlock = true;
                fence = stripped.substring(0, 3);
                String language = stripped.substring(3).trim();
                lines.add(language.isEmpty() ? "  ┌─ code" : "  ┌─ " + language);
                continue;
            }
            if (inCodeBlock) {
                if (stripped.startsWith(fence)) {
                    inCodeBlock = false;
                    lines.add("  └─");
                } else {
                    lines.add("  │ " + rawLine);
                }
                continue;
            }

            Matcher heading = HEADING.matcher(rawLine);
            if (heading.matches()) {
                lines.add("◆ " + cleanInline(heading.group(1)));
                continue;
            }
            if (TABLE_SEPARATOR.matcher(rawLine).matches()) {
                continue;
            }
            if (isTableRow(rawLine)) {
                lines.add(renderTableRow(rawLine));
                continue;
            }
            Matcher bullet = BULLET.matcher(rawLine);
            if (bullet.matches()) {
                lines.add(bullet.group(1) + "• " + cleanInline(bullet.group(2)));
                continue;
            }
            Matcher numbered = NUMBERED.matcher(rawLine);
            if (numbered.matches()) {
                lines.add(numbered.group(1) + numbered.group(2) + ". " + cleanInline(numbered.group(3)));
                continue;
            }
            if (stripped.startsWith(">")) {
                lines.add("  │ " + cleanInline(stripped.substring(1).stripLeading()));
                continue;
            }
            if (stripped.matches("(?:-{3,}|_{3,}|\\*{3,})")) {
                lines.add("  ────────");
                continue;
            }
            lines.add(cleanInline(rawLine));
        }
        if (inCodeBlock) {
            lines.add("  └─");
        }
        return List.copyOf(lines);
    }

    private static boolean isFenceStart(String line) {
        return line.startsWith("```") || line.startsWith("~~~");
    }

    private static boolean isTableRow(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.indexOf('|', 1) > 0;
    }

    private static String renderTableRow(String line) {
        String trimmed = line.trim();
        String body = trimmed.substring(1, trimmed.length() - 1);
        String[] cells = body.split("\\|", -1);
        ArrayList<String> rendered = new ArrayList<>(cells.length);
        for (String cell : cells) {
            rendered.add(cleanInline(cell.trim()));
        }
        return "  " + String.join("  │  ", rendered);
    }

    private static String cleanInline(String value) {
        String text = value;
        text = LINK.matcher(text).replaceAll("$1 ($2)");
        text = INLINE_CODE.matcher(text).replaceAll("‹$1›");
        text = text.replace("**", "").replace("__", "").replace("~~", "");
        return text;
    }
}
