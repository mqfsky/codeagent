package minicode.memory;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * 当前工作区可见指令文件经过预算处理后的不可变快照。
 * 文档按照从全局范围到最接近当前工作目录的顺序排列。
 */
public record MemorySnapshot(List<MemoryDocument> documents, int totalContentChars, boolean truncated) {
    public MemorySnapshot {
        documents = List.copyOf(Objects.requireNonNull(documents, "documents"));
        if (totalContentChars < 0) {
            throw new IllegalArgumentException("totalContentChars must be non-negative");
        }
        int actualTotal = documents.stream().mapToInt(document -> document.content().length()).sum();
        if (actualTotal != totalContentChars) {
            throw new IllegalArgumentException("totalContentChars must match document content lengths");
        }
    }

    public static MemorySnapshot empty() {
        return new MemorySnapshot(List.of(), 0, false);
    }

    public boolean isEmpty() {
        return documents.isEmpty();
    }

    /**
     * 生成供 {@code /memory} 命令展示的记忆文件报告。
     *
     * @param cwd 当前工作目录，用于尽量展示相对路径
     * @return 包含文件数量、层级、行数、字符数和首行预览的报告
     */
    public String renderReport(Path cwd) {
        Path actualCwd = Objects.requireNonNull(cwd, "cwd").toAbsolutePath().normalize();
        if (documents.isEmpty()) {
            return "No memory files loaded.";
        }
        StringJoiner report = new StringJoiner("\n\n");
        report.add("Memory files loaded: " + documents.size());
        for (int index = 0; index < documents.size(); index++) {
            MemoryDocument document = documents.get(index);
            String preview = document.content().lines().findFirst().orElse("<empty>");
            report.add((index + 1) + ". " + displayPath(actualCwd, document.path()) + "\n"
                    + "   scope: " + reportScope(document.scope()) + "\n"
                    + "   lines: " + document.content().lines().count() + "\n"
                    + "   chars: " + document.content().length() + "\n"
                    + "   preview: " + preview);
        }
        return report.toString();
    }

    /**
     * 在保持记忆层级顺序的前提下，将当前快照渲染为一段系统提示词。
     *
     * @return 可注入系统提示词的分层记忆文本；没有文档时返回空字符串
     */
    public String renderPromptSection() {
        if (documents.isEmpty()) {
            return "";
        }
        StringJoiner section = new StringJoiner("\n\n");
        section.add("""
                # Layered project memory
                Instructions are ordered from broad to specific. When instructions conflict, later and more specific instructions take precedence.
                """.strip());
        for (MemoryDocument document : documents) {
            section.add("## " + scopeLabel(document) + " from " + document.path() + "\n\n"
                    + document.content());
        }
        if (truncated) {
            section.add("_Some broader or oversized memory content was omitted to fit the prompt budget._");
        }
        return section.toString();
    }

    private static String scopeLabel(MemoryDocument document) {
        return switch (document.scope()) {
            case GLOBAL -> "Global instructions";
            case PROJECT_ROOT -> "Project-root instructions";
            case SUBDIRECTORY -> "Subdirectory instructions (depth " + document.depth() + ")";
            case RULE -> "Project rules (depth " + document.depth() + ")";
        };
    }

    private static String displayPath(Path cwd, Path file) {
        try {
            Path relative = cwd.relativize(file);
            String value = relative.toString();
            return (value.isBlank() ? file.toString() : value).replace('\\', '/');
        } catch (IllegalArgumentException exception) {
            return file.toString().replace('\\', '/');
        }
    }

    private static String reportScope(MemoryDocument.Scope scope) {
        return switch (scope) {
            case GLOBAL -> "global";
            case PROJECT_ROOT -> "project-root";
            case SUBDIRECTORY -> "subdirectory";
            case RULE -> "rules";
        };
    }
}
