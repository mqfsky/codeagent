package minicode.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import minicode.agent.model.AgentRunMode;
import minicode.tools.api.Tool;
import minicode.tools.api.ToolContext;
import minicode.tools.api.ValidationResult;
import minicode.tools.metadata.ToolCapability;
import minicode.tools.metadata.ToolMetadata;
import minicode.tools.metadata.ToolOrigin;
import minicode.tools.metadata.ToolStatus;
import minicode.tools.registry.ToolRegistry;
import minicode.tools.result.ToolResult;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** 为一次子 Agent 运行构建隔离且经过策略过滤的工具注册表。 */
public final class ChildToolRegistryFactory {
    private static final Set<String> ALWAYS_BLOCKED_NAMES = Set.of("agent", "ask_user");
    private static final String RUN_COMMAND = "run_command";

    private final Set<Path> blockedExternalExecutables;
    private final Set<String> blockedExternalExecutableNames;

    public ChildToolRegistryFactory() {
        this(Set.of());
    }

    public ChildToolRegistryFactory(Set<Path> blockedExternalExecutables) {
        Set<Path> normalizedPaths = new HashSet<>();
        Set<String> executableNames = new HashSet<>();
        for (Path executable : Objects.requireNonNull(blockedExternalExecutables,
                "blockedExternalExecutables")) {
            Path normalized = Objects.requireNonNull(executable, "blockedExternalExecutable")
                    .toAbsolutePath()
                    .normalize();
            normalizedPaths.add(normalized);
            Path fileName = normalized.getFileName();
            if (fileName != null) {
                executableNames.add(fileName.toString().toLowerCase(Locale.ROOT));
            }
        }
        this.blockedExternalExecutables = Set.copyOf(normalizedPaths);
        this.blockedExternalExecutableNames = Set.copyOf(executableNames);
    }

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
                .map(this::restrictExternalCommands)
                .forEach(child::register);
        return child;
    }

    private Tool restrictExternalCommands(Tool tool) {
        if (blockedExternalExecutables.isEmpty()
                || !RUN_COMMAND.equalsIgnoreCase(tool.metadata().name())) {
            return tool;
        }
        return new RestrictedChildCommandTool(
                tool,
                blockedExternalExecutables,
                blockedExternalExecutableNames
        );
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
        if (metadata.capabilities().contains(ToolCapability.EXTERNAL_WRITE)) {
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

    /**
     * Delegated agents keep normal build/test commands, but cannot invoke an executable reserved
     * for a parent-only external integration.
     */
    private record RestrictedChildCommandTool(Tool delegate,
                                              Set<Path> blockedPaths,
                                              Set<String> blockedNames) implements Tool {
        private RestrictedChildCommandTool {
            delegate = Objects.requireNonNull(delegate, "delegate");
            blockedPaths = Set.copyOf(Objects.requireNonNull(blockedPaths, "blockedPaths"));
            blockedNames = Set.copyOf(Objects.requireNonNull(blockedNames, "blockedNames"));
        }

        @Override
        public ToolMetadata metadata() {
            return delegate.metadata();
        }

        @Override
        public JsonNode inputSchema() {
            return delegate.inputSchema();
        }

        @Override
        public ValidationResult validateInput(JsonNode input) {
            return delegate.validateInput(input);
        }

        @Override
        public ToolResult run(JsonNode normalizedInput, ToolContext toolContext) {
            if (isBlockedCommand(normalizedInput, toolContext)) {
                return ToolResult.error(
                        "Delegated agents cannot invoke executables reserved for parent-only external actions. "
                                + "Return the requested external action to the parent agent."
                );
            }
            return delegate.run(normalizedInput, toolContext);
        }

        private boolean isBlockedCommand(JsonNode input, ToolContext context) {
            JsonNode commandNode = input == null ? null : input.get("command");
            if (commandNode == null || !commandNode.isTextual()) {
                return false;
            }
            String command = commandNode.asText().trim();
            if (command.isEmpty()) {
                return false;
            }
            try {
                Path commandPath = Path.of(command);
                Path resolved = commandPath.isAbsolute()
                        ? commandPath.normalize()
                        : context.cwd().resolve(commandPath).toAbsolutePath().normalize();
                if (blockedPaths.contains(resolved)) {
                    return true;
                }
                Path fileName = commandPath.getFileName();
                return fileName != null
                        && blockedNames.contains(fileName.toString().toLowerCase(Locale.ROOT));
            } catch (RuntimeException ignored) {
                return blockedNames.contains(command.toLowerCase(Locale.ROOT));
            }
        }
    }
}
