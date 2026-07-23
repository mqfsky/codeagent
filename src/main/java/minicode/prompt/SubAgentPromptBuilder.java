package minicode.prompt;

import minicode.agent.model.AgentRunMode;
import minicode.agent.runtime.AgentSpec;
import minicode.tools.api.Tool;
import minicode.tools.registry.ToolRegistry;

import java.nio.file.Path;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * 构建子 Agent 使用的最小化系统提示词。
 *
 * <p>该构建器有意与 {@link SystemPromptBuilder} 保持独立：子 Agent 不得继承父会话记忆、
 * 与用户交互的指令，以及已被子 Agent 策略移除的工具。</p>
 */
public final class SubAgentPromptBuilder {
    public record Input(Path cwd, AgentSpec spec, AgentRunMode runMode, ToolRegistry tools) {
        public Input {
            cwd = Objects.requireNonNull(cwd, "cwd").toAbsolutePath().normalize();
            spec = Objects.requireNonNull(spec, "spec");
            runMode = Objects.requireNonNull(runMode, "runMode");
            tools = Objects.requireNonNull(tools, "tools");
            if (!spec.supports(runMode)) {
                throw new IllegalArgumentException(
                        "Agent type " + spec.type() + " does not support background execution");
            }
        }
    }

    public String build(Path cwd, AgentSpec spec, AgentRunMode runMode, ToolRegistry tools) {
        return build(new Input(cwd, spec, runMode, tools));
    }

    // 构建子 agent prompt
    public String build(Input input) {
        Input actual = Objects.requireNonNull(input, "input");
        StringJoiner prompt = new StringJoiner("\n\n");
        prompt.add("You are a CodeAgent child agent with the built-in role: "
                + roleName(actual.spec()) + ".");
        prompt.add("Current cwd: " + actual.cwd());
        prompt.add(actual.spec().systemInstructions());
        prompt.add(commonBoundaries(actual.runMode()));
        prompt.add(toolSection(actual.tools()));
        prompt.add("""
                Response contract:
                - Work only on the delegated user message in this isolated context.
                - Use tools when evidence or implementation is needed.
                - Return a self-contained final result; do not assume the parent can see intermediate reasoning.
                - If a blocked operation is unavailable, explain the limitation and complete as much as possible.
                """.strip());
        return prompt.toString();
    }

    private static String commonBoundaries(AgentRunMode runMode) {
        String modeBoundary = runMode == AgentRunMode.BACKGROUND
                ? "This is a background delegated run. Complete it independently with the tools exposed below."
                : "This is a synchronous delegated run. Use only tools exposed below.";
        return """
                Child-agent boundaries:
                - Do not delegate to another agent and do not create or manage background tasks.
                - Do not ask the user questions. Make a reasonable in-scope assumption or report a concrete blocker.
                - Do not claim an unavailable or failed operation succeeded.
                - Parent chat history, project memory, and hidden session state are intentionally unavailable.
                """.strip() + "\n- " + modeBoundary;
    }

    private static String toolSection(ToolRegistry registry) {
        StringBuilder section = new StringBuilder("Available tools:");
        if (registry.list().isEmpty()) {
            return section.append("\n- none").toString();
        }
        for (Tool tool : registry.list()) {
            section.append("\n- ")
                    .append(tool.metadata().name())
                    .append(": ")
                    .append(tool.metadata().description())
                    .append("\n  schema: ")
                    .append(tool.inputSchema());
        }
        return section.toString();
    }

    private static String roleName(AgentSpec spec) {
        return spec.type().name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
}
