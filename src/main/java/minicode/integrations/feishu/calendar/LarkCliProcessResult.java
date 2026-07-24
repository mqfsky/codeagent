package minicode.integrations.feishu.calendar;

import java.util.Objects;

/**
 * Captured lark-cli process output.
 */
public record LarkCliProcessResult(int exitCode, String stdout, String stderr) {
    public LarkCliProcessResult {
        stdout = Objects.requireNonNull(stdout, "stdout");
        stderr = Objects.requireNonNull(stderr, "stderr");
    }
}
