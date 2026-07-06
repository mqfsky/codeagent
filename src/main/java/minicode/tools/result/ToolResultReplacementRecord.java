package minicode.tools.result;

import java.util.Objects;

/**
 * 大工具输出被持久化替换的记录。
 *
 * @param toolUseId 所属工具调用 id
 * @param toolName 被替换输出所属的工具名称
 * @param trigger 压缩触发来源
 * @param storageRef 持久化输出的存储引用
 * @param replacementContent 替换后放回上下文的内容
 * @param preview 原始输出预览
 * @param originalChars 原始输出字符数
 * @param previewChars 预览字符数
 * @param replacementLength 替换内容字符数
 */
public record ToolResultReplacementRecord(String toolUseId, String toolName, ToolResultReplacementTrigger trigger,
                                          ToolResultStorageRef storageRef, String replacementContent,
                                          String preview, long originalChars, long previewChars,
                                          long replacementLength) {
    public ToolResultReplacementRecord(String toolUseId, String toolName, ToolResultReplacementTrigger trigger,
                                       ToolResultStorageRef storageRef, String replacementContent,
                                       long originalLength, long replacementLength) {
        this(toolUseId, toolName, trigger, storageRef, replacementContent, "", originalLength, 0, replacementLength);
    }

    public ToolResultReplacementRecord {
        requireText(toolUseId, "toolUseId");
        requireText(toolName, "toolName");
        trigger = Objects.requireNonNull(trigger, "trigger");
        storageRef = Objects.requireNonNull(storageRef, "storageRef");
        requireText(replacementContent, "replacementContent");
        preview = Objects.requireNonNull(preview, "preview");
        if (originalChars < 0 || previewChars < 0 || replacementLength < 0) {
            throw new IllegalArgumentException("lengths must be non-negative");
        }
    }

    public long originalLength() {
        return originalChars;
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
