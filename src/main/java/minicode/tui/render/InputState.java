package minicode.tui.render;

import java.util.Objects;

/**
 * Renderer TUI 输入框状态。
 *
 * @param mode 输入模式
 * @param text 输入文本
 * @param cursor 光标位置
 */
public record InputState(Mode mode, String text, int cursor) {
    public InputState {
        mode = Objects.requireNonNull(mode, "mode");
        text = Objects.requireNonNull(text, "text");
        if (cursor < 0 || cursor > text.length()) {
            throw new IllegalArgumentException("cursor must be inside input text");
        }
    }

    public static InputState empty() {
        return new InputState(Mode.NORMAL, "", 0);
    }

    public static InputState of(String text, int cursor) {
        return new InputState(Mode.NORMAL, text, cursor);
    }

    public static InputState of(Mode mode, String text, int cursor) {
        return new InputState(mode, text, cursor);
    }

    public InputState withMode(Mode mode) {
        return new InputState(mode, text, cursor);
    }

    /**
     * Renderer TUI 输入框所处的交互阶段。
     *
     * <p>模式不仅决定输入框显示的提示符，还决定用户输入会被当作普通消息、
     * ask_user 回答、权限选择或权限拒绝反馈来处理。</p>
     */
    public enum Mode {
        /** 正常输入模式，提交内容会作为新的用户消息或内置命令处理。 */
        NORMAL,

        /** Agent 或工具正在执行，输入框暂时禁止编辑。 */
        BUSY,

        /** ask_user 工具正在等待回答，提交内容会作为问题答案。 */
        AWAITING_ASK_USER,

        /** 权限请求正在等待选择，提示符为，提交内容会被解析为权限选项。 */
        PENDING_PERMISSION,

        /** 用户选择了需要说明原因的拒绝项，提交内容作为拒绝原因。 */
        PERMISSION_FEEDBACK
    }
}
