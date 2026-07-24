package minicode.config;

import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Objects;

/** 用户级飞书日历集成配置。 */
public record FeishuCalendarConfig(boolean enabled, Path cliPath, ZoneId timezone,
                                   int defaultDurationMinutes, int defaultReminderMinutes,
                                   Duration timeout) {
    public static final Path DEFAULT_CLI_PATH = Path.of("/opt/homebrew/bin/lark-cli");
    public static final ZoneId DEFAULT_TIMEZONE = ZoneId.of("Asia/Shanghai");
    public static final int DEFAULT_DURATION_MINUTES = 30;
    public static final int DEFAULT_REMINDER_MINUTES = 5;
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    public FeishuCalendarConfig {
        cliPath = Objects.requireNonNull(cliPath, "cliPath").normalize();
        if (!cliPath.isAbsolute()) {
            throw new IllegalArgumentException("feishu calendar cliPath must be absolute");
        }
        timezone = Objects.requireNonNull(timezone, "timezone");
        if (defaultDurationMinutes <= 0 || defaultDurationMinutes > 1_440) {
            throw new IllegalArgumentException(
                    "feishu calendar defaultDurationMinutes must be between 1 and 1440");
        }
        if (defaultReminderMinutes < 0 || defaultReminderMinutes > 20_160) {
            throw new IllegalArgumentException(
                    "feishu calendar defaultReminderMinutes must be between 0 and 20160");
        }
        timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("feishu calendar timeout must be positive");
        }
    }

    public static FeishuCalendarConfig defaults(boolean enabled) {
        return new FeishuCalendarConfig(
                enabled,
                DEFAULT_CLI_PATH,
                DEFAULT_TIMEZONE,
                DEFAULT_DURATION_MINUTES,
                DEFAULT_REMINDER_MINUTES,
                DEFAULT_TIMEOUT
        );
    }
}
