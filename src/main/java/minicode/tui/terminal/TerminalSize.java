package minicode.tui.terminal;

/**
 * 终端尺寸。
 *
 * @param columns 终端列数
 * @param rows 终端行数
 */
public record TerminalSize(int columns, int rows) {
    public TerminalSize {
        if (columns < 1) {
            throw new IllegalArgumentException("columns must be positive");
        }
        if (rows < 1) {
            throw new IllegalArgumentException("rows must be positive");
        }
    }
}
