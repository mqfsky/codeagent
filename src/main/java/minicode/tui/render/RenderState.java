package minicode.tui.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Renderer TUI 的整体渲染状态。
 *
 * @param transcript 转写区域内容块列表
 * @param status 状态栏内容
 * @param input 输入框状态
 * @param scrollOffset 滚动偏移量
 * @param contextBadge 上下文占用徽标文本；为空表示不展示
 */
public record RenderState(List<TranscriptBlock> transcript, StatusState status, InputState input, int scrollOffset,
                          Optional<String> contextBadge) {
    public RenderState {
        transcript = List.copyOf(Objects.requireNonNull(transcript, "transcript"));
        status = Objects.requireNonNull(status, "status");
        input = Objects.requireNonNull(input, "input");
        contextBadge = Objects.requireNonNull(contextBadge, "contextBadge");
        if (scrollOffset < 0) {
            throw new IllegalArgumentException("scrollOffset must be non-negative");
        }
    }

    public static RenderState empty() {
        return new RenderState(List.of(), StatusState.empty(), InputState.empty(), 0, Optional.empty());
    }

    public RenderState withTranscript(List<TranscriptBlock> transcript) {
        return new RenderState(transcript, status, input, scrollOffset, contextBadge);
    }

    /**
     * 将一个内容块合并到转写区域，并返回更新后的渲染状态。
     *
     * <p>{@code RenderState} 是不可变 record，因此本方法不会修改当前实例。工具块会按
     * {@code toolUseId} 更新同一次工具调用的展示内容，权限块会按块 id 更新同一次权限请求，
     * 其他内容块则仅在 id 尚不存在时追加，从而避免界面出现重复记录。</p>
     *
     * @param block 需要合并到转写区域的内容块，不能为 {@code null}
     * @return 包含合并后转写列表的新 {@link RenderState}
     * @throws NullPointerException 当 {@code block} 为 {@code null} 时抛出
     */
    public RenderState appendTranscript(TranscriptBlock block) {
        // 复制当前不可变列表，后续所有替换和追加都在新列表上完成。
        ArrayList<TranscriptBlock> next = new ArrayList<>(transcript);
        TranscriptBlock actualBlock = Objects.requireNonNull(block, "block");

        // 工具开始和工具结果共享 toolUseId：结果到达后更新原位置，避免显示成两次工具调用。
        if (actualBlock.toolUseId().isPresent()) {
            String toolUseId = actualBlock.toolUseId().orElseThrow();
            for (int index = 0; index < next.size(); index++) {
                TranscriptBlock existing = next.get(index);
                if (existing.toolUseId().filter(toolUseId::equals).isPresent()
                        && existing.kind() == TranscriptBlock.Kind.TOOL
                        && actualBlock.kind() == TranscriptBlock.Kind.TOOL) {
                    // 更新工具状态和输出时，尽量保留开始阶段已经展示的工具摘要。
                    next.set(index, preserveToolSummary(existing, actualBlock));
                    return withTranscript(next);
                }
            }
        }

        // 同一权限请求会经历等待、允许或拒绝等状态，使用相同 id 替换原审计块。
        for (int index = 0; index < next.size(); index++) {
            TranscriptBlock existing = next.get(index);
            if (existing.id().equals(actualBlock.id())
                    && existing.kind() == TranscriptBlock.Kind.PERMISSION
                    && actualBlock.kind() == TranscriptBlock.Kind.PERMISSION) {
                next.set(index, actualBlock);
                return withTranscript(next);
            }
        }

        // 普通内容块只追加一次；相同 id 已存在时直接保持当前列表，防止重复渲染。
        if (next.stream().noneMatch(existing -> existing.id().equals(actualBlock.id()))) {
            next.add(actualBlock);
        }
        // withTranscript(...) 会创建新的 RenderState，并保留状态栏、输入框、滚动位置等其他字段。
        return withTranscript(next);
    }

    public RenderState appendTranscript(List<TranscriptBlock> blocks) {
        ArrayList<TranscriptBlock> next = new ArrayList<>(transcript);
        next.addAll(Objects.requireNonNull(blocks, "blocks"));
        return withTranscript(next);
    }

    public RenderState withStatus(StatusState status) {
        return new RenderState(transcript, status, input, scrollOffset, contextBadge);
    }

    public RenderState clearStatus() {
        return withStatus(StatusState.empty());
    }

    public RenderState withInput(InputState input) {
        return new RenderState(transcript, status, input, scrollOffset, contextBadge);
    }

    public RenderState withScrollOffset(int scrollOffset) {
        return new RenderState(transcript, status, input, scrollOffset, contextBadge);
    }

    public RenderState withContextBadge(String contextBadge) {
        return new RenderState(transcript, status, input, scrollOffset,
                Objects.requireNonNull(contextBadge, "contextBadge").isBlank()
                        ? Optional.empty()
                        : Optional.of(contextBadge));
    }

    public RenderState clearContextBadge() {
        return new RenderState(transcript, status, input, scrollOffset, Optional.empty());
    }

    private static TranscriptBlock preserveToolSummary(TranscriptBlock existing, TranscriptBlock replacement) {
        String existingText = existing.text();
        String replacementText = replacement.text();
        if (existingText.isBlank() || replacementText.startsWith(existingText)) {
            return replacement;
        }
        String summary = firstLine(existingText);
        if (summary.isBlank() || replacementText.contains(summary)) {
            return replacement;
        }
        return replacement.withText(replacementText.isBlank()
                ? summary
                : summary + System.lineSeparator() + replacementText);
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline);
    }
}
