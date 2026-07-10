package minicode.edit;

import minicode.permissions.model.PermissionResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class UnifiedDiffBuilder {
    private static final int DEFAULT_MAX_PREVIEW_CHARS = 4096;
    private static final int CONTEXT_LINES = 3;
    private static final int LCS_CUTOFF = 4_000_000;
    private static final String TRUNCATED_MARKER = "[diff preview truncated]";

    private UnifiedDiffBuilder() {
    }

    public static EditReview build(Path path, PermissionResource.EditOperation operation, String summary,
                                   Optional<String> beforeContent, String afterContent) {
        return build(path, operation, summary, beforeContent, afterContent, DEFAULT_MAX_PREVIEW_CHARS);
    }

    public static EditReview build(Path path, PermissionResource.EditOperation operation, String summary,
                                   Optional<String> beforeContent, String afterContent, int maxPreviewChars) {
        // 参数检查
        Path actualPath = Objects.requireNonNull(path, "path");
        PermissionResource.EditOperation actualOperation = Objects.requireNonNull(operation, "operation");
        if (maxPreviewChars <= 0) {
            throw new IllegalArgumentException("maxPreviewChars must be positive");
        }
        Optional<String> normalizedBefore = Objects.requireNonNull(beforeContent, "beforeContent")
                .map(UnifiedDiffBuilder::normalizeLineEndings);
        String normalizedAfter = normalizeLineEndings(Objects.requireNonNull(afterContent, "afterContent"));

        boolean beforeExists = normalizedBefore.isPresent();
        // 构造 UI上的输出 diff
        String preview = renderPreview(actualPath, normalizedBefore, normalizedAfter);
        boolean truncated = preview.length() > maxPreviewChars;
        if (truncated) {
            // 缩短
            preview = truncate(preview, maxPreviewChars);
        }

        long beforeChars = normalizedBefore.map(String::length).orElse(0);
        long afterChars = normalizedAfter.length();
        String fingerprint = fingerprint(actualPath, actualOperation, beforeExists, normalizedBefore, normalizedAfter);
        return new EditReview(
                actualPath,
                actualOperation,
                summary,
                preview,
                beforeChars,
                afterChars,
                beforeExists,
                truncated,
                fingerprint,
                Optional.of("sha256:" + fingerprint)
        );
    }

    /**
     * 把“修改前内容”和“修改后内容”转换成用户能看的 unified diff 字符串。
     * @param path
     * @param beforeContent
     * @param afterContent
     * @return
     */
    private static String renderPreview(Path path, Optional<String> beforeContent, String afterContent) {
        // path 格式化
        String pathText = displayPath(path);
        String normalizedBefore = beforeContent.orElse("");
        // 如果没有修改
        if (beforeContent.isPresent() && normalizedBefore.equals(afterContent)) {
            return "--- a/" + pathText + "\n"
                    + "+++ b/" + pathText + "\n"
                    + "@@ no changes @@\n";
        }

        List<String> beforeLines = toLines(normalizedBefore);
        List<String> afterLines = toLines(afterContent);

        // 旧内容不存在，生成显示的字符串
        if (!beforeContent.isPresent()) {
            return renderCreate(pathText, afterLines);
        }
        // 旧内容存在，新内容不存在，生成显示的字符串
        if (afterContent.isEmpty()) {
            return renderDelete(pathText, beforeLines);
        }
        // 都存在，构造输出结果，涉及 LCS，贪心
        return renderMultiHunk(pathText, beforeLines, afterLines);
    }

    /**
     * 类似
     * Diff preview:
     * --- /dev/null
     * +++ b/workspace/test.txt
     * @@ -0,0 +1,1 @@
     * +new
     * @param pathText
     * @param afterLines
     * @return
     */
    private static String renderCreate(String pathText, List<String> afterLines) {
        StringBuilder builder = new StringBuilder();
        builder.append("--- /dev/null\n");
        builder.append("+++ b/").append(pathText).append('\n');
        builder.append(String.format("@@ -0,0 +1, %d @@\n", afterLines.size()));
        for (String line : afterLines) {
            builder.append('+').append(line).append('\n');
        }
        return builder.toString();
    }

    private static String renderDelete(String pathText, List<String> beforeLines) {
        StringBuilder builder = new StringBuilder();
        builder.append("--- a/").append(pathText).append('\n');
        builder.append("+++ /dev/null\n");
        builder.append(String.format("@@ -1,%d +0,0 @@\n", beforeLines.size()));
        for (String line : beforeLines) {
            builder.append('-').append(line).append('\n');
        }
        return builder.toString();
    }

    private static String renderMultiHunk(String pathText, List<String> beforeLines, List<String> afterLines) {
        // 判断每行是保留、删除还是新增
        List<DiffEntry> entries = buildEntries(beforeLines, afterLines);
        // 如果 entries 里每行都是 keep，返回np changes
        if (entries.stream().allMatch(entry -> entry.type() == DiffType.KEEP)) {
            return "--- a/" + pathText + "\n"
                    + "+++ b/" + pathText + "\n"
                    + "@@ no changes @@\n";
        }

        // 需要展示给用户的范围列表
        List<Range> ranges = buildRanges(entries);
        StringBuilder builder = new StringBuilder();
        // 修改前后文件名相同
        builder.append("--- a/").append(pathText).append('\n');
        builder.append("+++ b/").append(pathText).append('\n');

        /**
         * @@ -10, 3 +10,4 @@
         *  context line
         * -old line
         * +new line
         *  context line
         */
        for (Range range : ranges) {
            // 按照 ranges 分割 entries
            List<DiffEntry> slice = entries.subList(range.start(), range.endInclusive() + 1);

            // oldStart 是 hunk 在旧文件中的起始行号。
            long oldStart = slice.stream().filter(DiffEntry::contributesToOld).findFirst()
                    .map(entry -> entry.oldIndex() + 1L)
                    .orElse(0L);

            // newStart 是 hunk 在新文件中的起始行号
            long newStart = slice.stream().filter(DiffEntry::contributesToNew).findFirst()
                    .map(entry -> entry.newIndex() + 1L)
                    .orElse(0L);
            long oldCount = slice.stream().filter(DiffEntry::contributesToOld).count();
            long newCount = slice.stream().filter(DiffEntry::contributesToNew).count();

            builder.append(String.format("@@ -%d,%d +%d,%d @@\n", oldStart, oldCount, newStart, newCount));
            // 把每个 entry 输出成一行
            for (DiffEntry entry : slice) {
                builder.append(entry.prefix()).append(entry.text()).append('\n');
            }
        }
        return builder.toString();
    }

    private static List<DiffEntry> buildEntries(List<String> beforeLines, List<String> afterLines) {
        // 大文件走降级逻辑，改用更简单的贪心算法，避免内存和计算量过大
        if ((long) beforeLines.size() * (long) afterLines.size() > LCS_CUTOFF) {
            return buildGreedyEntries(beforeLines, afterLines);
        }
        // 最长公共子序列
        // lcs[i][j] 表示：从旧文件第 i 行和新文件第 j 行开始，后面最多还能找到多少行相同内容
        int[][] lcs = buildLcsTable(beforeLines, afterLines);
        List<DiffEntry> entries = new ArrayList<>();
        int i = 0;
        int j = 0;
        // 从lcs表左上角 (0,0) 开始移动
        while (i < beforeLines.size() || j < afterLines.size()) {
            // 两行相同 → 右下移动，KEEP
            if (i < beforeLines.size() && j < afterLines.size()
                    && beforeLines.get(i).equals(afterLines.get(j))) {
                entries.add(new DiffEntry(DiffType.KEEP, beforeLines.get(i), i, j));
                i++;
                j++;
                continue;
            }
            // 既然不相同，则需要判断是新文件新增了一行，还是旧文件删除了一行
            // lcs[i][j + 1] >= lcs[i + 1][j]，说明当前格子的右边比下边大，向右移动
            if (j < afterLines.size() && (i == beforeLines.size() // 旧文件遍历完，还有不相同的行，只能是新增
                    || lcs[i][j + 1] >= lcs[i + 1][j])) {
                // 如果lcs[i][j + 1] >= lcs[i + 1][j]，则取lcs[i][j + 1]
                // lcs[i][j + 1] 说明跳过当前新文件行，去下一行，说明当前跳过的行是旧文件没有的，因此是新增行，所以这种情况下是 INSERT
                entries.add(new DiffEntry(DiffType.INSERT, afterLines.get(j), i, j));
                j++;
            } else { // 否则向下移动
                entries.add(new DiffEntry(DiffType.DELETE, beforeLines.get(i), i, j));
                i++;
            }
        }
        return entries;
    }

    private static List<DiffEntry> buildGreedyEntries(List<String> beforeLines, List<String> afterLines) {
        List<DiffEntry> entries = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < beforeLines.size() || j < afterLines.size()) {
            if (i < beforeLines.size() && j < afterLines.size()
                    && beforeLines.get(i).equals(afterLines.get(j))) {
                entries.add(new DiffEntry(DiffType.KEEP, beforeLines.get(i), i, j));
                i++;
                j++;
                continue;
            }
            if (i < beforeLines.size()) {
                entries.add(new DiffEntry(DiffType.DELETE, beforeLines.get(i), i, j));
                i++;
            }
            if (j < afterLines.size()) {
                entries.add(new DiffEntry(DiffType.INSERT, afterLines.get(j), i, j));
                j++;
            }
        }
        return entries;
    }

    // lcs[i][j] 表示：从旧文件第 i 行和新文件第 j 行开始，后面最多还能找到多少行相同内容
    // LCS 找出新旧文件中“顺序没有改变、可以保留”的最多行，其余旧行删除，其余新行新增。
    // 子序列不要求连续，只要求顺序一致
    private static int[][] buildLcsTable(List<String> beforeLines, List<String> afterLines) {
        int[][] table = new int[beforeLines.size() + 1][afterLines.size() + 1];
        for (int i = beforeLines.size() - 1; i >= 0; i--) {
            for (int j = afterLines.size() - 1; j >= 0; j--) {
                if (beforeLines.get(i).equals(afterLines.get(j))) {
                    table[i][j] = table[i + 1][j + 1] + 1;
                } else {
                    table[i][j] = Math.max(
                            table[i + 1][j],
                            table[i][j + 1]);
                }
            }
        }
        return table;
    }

    /**
     * diff hunk 的范围切分, 从一整串 DiffEntry 里找出哪些区间需要展示成 diff hunk，只打印有变化的区域
     * @param entries
     * @return
     */
    private static List<Range> buildRanges(List<DiffEntry> entries) {
        // 要展示的范围集合
        List<Range> ranges = new ArrayList<>();
        int index = 0;
        while (index < entries.size()) {
            // 没变化则跳过
            while (index < entries.size() && entries.get(index).type() == DiffType.KEEP) {
                index++;
            }
            if (index >= entries.size()) {
                break;
            }

            // 从开始变化的地方往前退 3 行
            int start = Math.max(0, index - CONTEXT_LINES);

            int lastChange = index; // 最近一次看到 DELETE/INSERT 的位置
            int end = index; // 当前 hunk 暂定结束位置
            int keepTail = 0; // 变化之后连续看到多少行 KEEP
            int cursor = index; // 当前扫描到 entries 的哪个位置
            while (cursor < entries.size()) {
                // 变化之后的普通上下文行可以保留，但最多保留 3 行。超过 3 行则退出
                if (entries.get(cursor).type() == DiffType.KEEP) {
                    keepTail++;
                    if (keepTail > CONTEXT_LINES) {
                        break;
                    }
                } else {
                    lastChange = cursor;
                    keepTail = 0;
                }
                end = cursor;
                cursor++;
            }
            end = Math.min(entries.size() - 1, Math.max(lastChange, end) );
            ranges.add(new Range(start, end));
            index = end + 1;
        }
        return mergeRanges(ranges);
    }

    private static List<Range> mergeRanges(List<Range> ranges) {
        if (ranges.isEmpty()) {
            return ranges;
        }
        List<Range> merged = new ArrayList<>();
        Range current = ranges.getFirst();
        for (int i = 1; i < ranges.size(); i++) {
            Range next = ranges.get(i);
            if (next.start() <= current.endInclusive() + 1) {
                current = new Range(current.start(), Math.max(current.endInclusive(), next.endInclusive()));
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    private static List<String> toLines(String content) {
        if (content.isEmpty()) {
            return List.of();
        }
        return content.lines().toList();
    }

    private static String truncate(String preview, int maxPreviewChars) {
        String marker = "\n" + TRUNCATED_MARKER;
        if (maxPreviewChars <= marker.length()) {
            return marker.substring(0, maxPreviewChars);
        }
        return preview.substring(0, maxPreviewChars - marker.length()) + marker;
    }

    private static String normalizeLineEndings(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String displayPath(Path path) {
        return path.normalize().toString().replace('\\', '/');
    }

    private static String fingerprint(Path path, PermissionResource.EditOperation operation, boolean beforeExists,
                                      Optional<String> beforeContent, String afterContent) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(displayPath(path).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(operation.name().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update((byte) (beforeExists ? 1 : 0));
            digest.update((byte) 0);
            digest.update(beforeContent.orElse("").getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(afterContent.getBytes(StandardCharsets.UTF_8));
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            int unsigned = value & 0xFF;
            builder.append(Character.forDigit((unsigned >>> 4) & 0xF, 16));
            builder.append(Character.forDigit(unsigned & 0xF, 16));
        }
        return builder.toString();
    }

    private enum DiffType {
        KEEP,
        DELETE,
        INSERT
    }

    /**
     * 统一 diff 构建过程中的单条差异。
     *
     * @param type 类型或事件类型
     * @param text 输入文本
     * @param oldIndex 旧文本中的索引
     * @param newIndex 新文本中的索引
     */
    private record DiffEntry(DiffType type, String text, int oldIndex, int newIndex) {
        boolean contributesToOld() {
            return type == DiffType.KEEP || type == DiffType.DELETE;
        }

        boolean contributesToNew() {
            return type == DiffType.KEEP || type == DiffType.INSERT;
        }

        char prefix() {
            return switch (type) {
                case KEEP -> ' ';
                case DELETE -> '-';
                case INSERT -> '+';
            };
        }
    }

    /**
     * 闭区间范围描述。
     *
     * @param start 起始位置
     * @param endInclusive 闭区间结束位置
     */
    private record Range(int start, int endInclusive) {
    }
}
