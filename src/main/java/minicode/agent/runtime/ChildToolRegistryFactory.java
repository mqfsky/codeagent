package minicode.agent.runtime;

import minicode.agent.model.AgentRunMode;
import minicode.tools.api.Tool;
import minicode.tools.metadata.ToolCapability;
import minicode.tools.metadata.ToolMetadata;
import minicode.tools.metadata.ToolOrigin;
import minicode.tools.metadata.ToolStatus;
import minicode.tools.registry.ToolRegistry;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** 为一次子 Agent 运行构建隔离且经过策略过滤的工具注册表。 */
public final class ChildToolRegistryFactory {
    private static final Set<String> ALWAYS_BLOCKED_NAMES = Set.of("agent", "ask_user");
    private static final Set<ToolCapability> BACKGROUND_CAPABILITIES = Set.of(ToolCapability.READ);

    public ToolRegistry create(ToolRegistry parentRegistry, AgentSpec spec, AgentRunMode runMode) {
        ToolRegistry source = Objects.requireNonNull(parentRegistry, "parentRegistry");
        AgentSpec actualSpec = Objects.requireNonNull(spec, "spec");
        AgentRunMode actualMode = Objects.requireNonNull(runMode, "runMode");
        if (!actualSpec.supports(actualMode)) {
            throw new IllegalArgumentException(
                    "Agent type " + actualSpec.type() + " does not support background execution");
        }

        ToolRegistry child = new ToolRegistry();
        source.list().stream()
                .filter(tool -> allowed(tool, actualSpec, actualMode))
                .forEach(child::register);
        return child;
    }

    private static boolean allowed(Tool tool, AgentSpec spec, AgentRunMode runMode) {
        ToolMetadata metadata = Objects.requireNonNull(tool, "tool").metadata();
        String normalizedName = metadata.name().toLowerCase(Locale.ROOT);
        if (ALWAYS_BLOCKED_NAMES.contains(normalizedName) || normalizedName.startsWith("task_")) {
            return false;
        }
        if (metadata.status() != ToolStatus.AVAILABLE) {
            return false;
        }
        if (metadata.capabilities().contains(ToolCapability.ASK_USER)
                || metadata.capabilities().contains(ToolCapability.BACKGROUND_TASK)) {
            return false;
        }
        if (runMode == AgentRunMode.BACKGROUND && metadata.origin() == ToolOrigin.MCP) {
            return false;
        }

        Set<ToolCapability> allowedCapabilities = runMode == AgentRunMode.BACKGROUND
                ? BACKGROUND_CAPABILITIES
                : spec.allowedCapabilities();
        return !metadata.capabilities().isEmpty()
                && allowedCapabilities.containsAll(metadata.capabilities());
    }
}
