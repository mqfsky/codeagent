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

    public ToolRegistry create(ToolRegistry parentRegistry, AgentSpec spec, AgentRunMode runMode) {
        ToolRegistry source = Objects.requireNonNull(parentRegistry, "parentRegistry");
        AgentSpec actualSpec = Objects.requireNonNull(spec, "spec");
        AgentRunMode actualMode = Objects.requireNonNull(runMode, "runMode");

        if (!actualSpec.supports(actualMode)) {
            throw new IllegalArgumentException(
                    "Agent type " + actualSpec.type() + " does not support background execution");
        }

        ToolRegistry child = new ToolRegistry();
        // 载入子 agent 能够使用的工具
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
        // Mewcode 会让 MCP 工具直接通过角色过滤；具体调用仍经过工具自身的权限链。
        if (metadata.origin() == ToolOrigin.MCP) {
            return true;
        }

        return !metadata.capabilities().isEmpty()
                && spec.allowedCapabilities().containsAll(metadata.capabilities());
    }
}
