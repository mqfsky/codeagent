package minicode.agent.runtime;

import minicode.agent.model.AgentRunMode;
import minicode.agent.model.AgentType;
import minicode.tools.metadata.ToolCapability;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 单个内置子 Agent 角色的不可变配置。
 *
 * @param type {@code agent} 工具对外暴露的角色标识
 * @param description 简短的角色说明
 * @param systemInstructions 仅用于子 Agent 上下文的角色专属指令
 * @param allowedCapabilities 该角色可使用的工具能力
 * @param maxSteps 单次子 Agent 运行允许的最大模型/工具步骤数
 * @param backgroundAllowed 该角色是否允许作为后台任务运行
 */
public record AgentSpec(
        AgentType type,
        String description,
        String systemInstructions,
        Set<ToolCapability> allowedCapabilities,
        int maxSteps,
        boolean backgroundAllowed
) {
    private static final AgentSpec EXPLORE = new AgentSpec(
            AgentType.EXPLORE,
            "Read-only repository exploration and evidence gathering.",
            """
                    Explore the repository efficiently and report concrete evidence.
                    Locate relevant files, definitions, references, and execution paths.
                    Stay read-only. Commands may only inspect data; never use them to mutate files or repository state.
                    Return a concise answer with exact file paths and the most important findings.
                    """.strip(),
            Set.of(ToolCapability.READ, ToolCapability.COMMAND),
            30,
            true
    );

    private static final AgentSpec PLAN = new AgentSpec(
            AgentType.PLAN,
            "Read-only software design and implementation planning.",
            """
                    Act as a software architect and implementation planner.
                    Inspect the repository before proposing changes and follow its existing design patterns.
                    Stay read-only. Commands may only inspect data; never use them to mutate files or repository state.
                    Produce an ordered, implementation-ready plan with dependencies, risks, verification, and critical files.
                    """.strip(),
            Set.of(ToolCapability.READ, ToolCapability.COMMAND),
            15,
            true
    );

    private static final AgentSpec GENERAL_PURPOSE = new AgentSpec(
            AgentType.GENERAL_PURPOSE,
            "General-purpose coding agent for delegated implementation work.",
            """
                    Complete the delegated task end to end using the available repository tools.
                    Inspect relevant code before changing it, keep edits scoped, and verify important behavior.
                    File writes, commands, and external tools remain subject to the parent's permission boundary.
                    Report the completed outcome, verification performed, and any remaining limitation.
                    """.strip(),
            Set.of(ToolCapability.READ, ToolCapability.WRITE, ToolCapability.COMMAND),
            200,
            true
    );

    private static final Map<AgentType, AgentSpec> BUILT_INS = builtInsByType();

    public AgentSpec {
        type = Objects.requireNonNull(type, "type");
        description = requireText(description, "description");
        systemInstructions = requireText(systemInstructions, "systemInstructions");
        allowedCapabilities = Set.copyOf(Objects.requireNonNull(allowedCapabilities, "allowedCapabilities"));
        if (allowedCapabilities.isEmpty()) {
            throw new IllegalArgumentException("allowedCapabilities must not be empty");
        }
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
    }

    /** 返回与 Mewcode 内置角色对齐的固定规范。 */
    public static AgentSpec forType(AgentType type) {
        AgentSpec spec = BUILT_INS.get(Objects.requireNonNull(type, "type"));
        if (spec == null) {
            throw new IllegalArgumentException("Unsupported agent type: " + type);
        }
        return spec;
    }

    /** 返回按类型索引的三个固定角色规范。 */
    public static Map<AgentType, AgentSpec> builtIns() {
        return BUILT_INS;
    }

    public boolean supports(AgentRunMode runMode) {
        AgentRunMode actualMode = Objects.requireNonNull(runMode, "runMode");
        return actualMode == AgentRunMode.SYNC || backgroundAllowed;
    }

    private static Map<AgentType, AgentSpec> builtInsByType() {
        EnumMap<AgentType, AgentSpec> specs = new EnumMap<>(AgentType.class);
        specs.put(EXPLORE.type(), EXPLORE);
        specs.put(PLAN.type(), PLAN);
        specs.put(GENERAL_PURPOSE.type(), GENERAL_PURPOSE);
        return Map.copyOf(specs);
    }

    private static String requireText(String value, String name) {
        String actual = Objects.requireNonNull(value, name);
        if (actual.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return actual;
    }
}
