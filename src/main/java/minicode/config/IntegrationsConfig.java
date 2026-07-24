package minicode.config;

import java.util.Objects;
import java.util.Optional;

/** CodeAgent 的可选外部集成配置。 */
public record IntegrationsConfig(Optional<FeishuCalendarConfig> feishuCalendar) {
    public IntegrationsConfig {
        feishuCalendar = Objects.requireNonNull(feishuCalendar, "feishuCalendar");
    }

    public static IntegrationsConfig empty() {
        return new IntegrationsConfig(Optional.empty());
    }
}
