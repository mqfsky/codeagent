# 子 Agent 显示完成但未返回最终报告

## 背景

- 发生日期：2026-07-20
- 运行方式：主 Agent 通过 `agent` 工具同步调用 `explore` 子 Agent
- 任务内容：分析当前 CodeAgent 仓库并返回完整分析报告
- 当时使用的模型：`deepseek-ai/DeepSeek-V4-Flash`
- Provider：SiliconFlow OpenAI-compatible API

## 修复状态

该问题已修复。子 Agent 运行时现在会对明显异常的完成文本进行有限重试，重试后仍不合格则返回 `FAILED`；父 Agent 只接收终态的最终回答，不再接收拼接后的过程消息。

## 一、问题现象

终端中可以看到子 Agent 经历了以下状态：

```text
[sync] explore running
[sync] explore tool list_files started
[sync] explore tool run_command started
[sync] explore tool list_files completed
[sync] explore tool run_command completed
[sync] explore tool read_file started
[sync] explore tool read_file completed
[sync] explore tool read_file failed
[sync] explore completed
```

从生命周期状态看，子 Agent 已经正常进入 `completed`；但主 Agent 收到的结果主要是大量过程性文字，例如反复出现：

```text
Let me read...
Let me check...
Let me explore...
```

最终没有得到结构化、可直接交付的仓库分析报告，因此主 Agent 判断“子 Agent 执行了大量探索工作，但最终的分析报告没有完整呈现”。

本次实际返回内容约为 40,963 个字符，其中 `Let me` 出现约 453 次，末尾还包含类似 DSML/XML 的伪工具调用标记。由此可以确认：

1. 子 Agent 确实执行了探索操作。
2. `completed` 只代表运行流程正常结束，不代表任务结果在语义上完整。
3. 问题不是权限等待，也不是工具结果持久化机制导致的截断。
4. 单次 `read_file failed` 不是子 Agent 结束的直接原因。任务要求检查的 `AGENTS.md`、`CODEAGENT.md` 和 `.codeagent/rules/` 在当前仓库中不存在，读取不存在的文件可能产生该失败事件；子 Agent 可以在收到错误结果后继续执行。

## 二、问题原因

### 2.1 直接触发原因：模型输出退化

模型在完成部分工具调用后，没有生成最终分析报告，而是陷入了重复的计划描述。输出末尾尝试生成伪工具调用标记，但没有通过 OpenAI-compatible 协议返回标准的 `message.tool_calls`。

这说明本次模型输出同时存在两个问题：

- 重复生成“接下来准备做什么”，没有收敛到最终答案。
- 把工具调用表达成正文中的标记，而不是 Provider 协议要求的结构化 `tool_calls`。

### 2.2 修复前 Adapter 将伪工具调用当作普通文本

`OpenAIModelAdapter` 只会从响应的 `message.tool_calls` 字段解析工具调用。正文中的 DSML/XML 标记不会被识别为工具调用，而会继续作为普通 `content` 处理。

对于没有 `<final>`、`[FINAL]`、`<progress>` 或 `[PROGRESS]` 前缀的普通文本，Adapter 会将其标记为 `AssistantKind.UNSPECIFIED`。

相关代码：

- `src/main/java/minicode/model/openai/OpenAIModelAdapter.java`

### 2.3 修复前 AgentLoop 将 `UNSPECIFIED` 视为正常完成

修复前，`AgentLoop` 把 `FINAL` 和 `UNSPECIFIED` 放在同一分支处理：只要文本非空，就创建 `AssistantMessage` 并返回 `AgentTurnResult.finalResult(...)`。

因此，即使正文只是重复计划或伪工具调用，只要它是非空的 `UNSPECIFIED` 文本，子 Agent 仍会被标记为正常完成。

相关代码：

- `src/main/java/minicode/core/loop/AgentLoop.java`

### 2.4 修复前 AgentRunResultMapper 混合了过程输出和最终输出

修复前，`AgentRunResultMapper.assistantOutput(...)` 会遍历子 Agent 的全部消息，同时收集：

- `AssistantMessage`
- `AssistantProgressMessage`

然后使用空行拼接成最终 `AgentRunResult.output`。这会把子 Agent 的过程性说明、重复规划和最后一条正文全部返回给父 Agent，而不是只返回最终报告。

相关代码：

- `src/main/java/minicode/agent/runtime/AgentRunResultMapper.java`

### 2.5 修复前缺少子 Agent 专用的结果质量门

同步 `AgentTool` 只检查 `AgentRunResult.successful()`。修复前，只要没有错误或取消状态，就会返回成功的 `ToolResult`，并在 TUI 中显示 `completed`。

修复前没有检查以下内容：

- 是否真正生成了最终报告。
- 最终内容是否仍然是过程性规划。
- 是否包含无法解析的伪工具调用。
- 是否出现严重重复或输出退化。

相关代码：

- `src/main/java/minicode/tools/builtin/AgentTool.java`

### 2.6 根本原因

根本原因不是某一次文件读取失败，而是修复前系统把“运行时正常结束”直接等同于“任务完成”：

```text
模型返回非空普通文本
    -> Adapter 标记为 UNSPECIFIED
    -> AgentLoop 将 UNSPECIFIED 当作 FINAL
    -> ResultMapper 拼接全部过程文本
    -> AgentTool 返回成功
    -> TUI 显示 completed
```

整个链路缺少对子 Agent 最终交付物的质量校验和异常输出恢复机制。

## 三、已实施的解决方案

### 3.1 只向父 Agent 返回终态答案

已调整 `AgentRunResultMapper`：

- 只有 `FINAL` 状态且最后一条消息是非空 `AssistantMessage` 时才返回成功。
- 不再把 `AssistantProgressMessage` 拼入最终结果。
- 过程消息仍可用于 TUI 展示和诊断，但不应污染父 Agent 接收到的任务结果。
- 失败状态不会再从历史消息中捞取旧的工具调用说明作为输出。
- 如果没有有效的终态 `AssistantMessage`，返回失败，不能构造成功结果。

该修改可以解决“过程输出淹没最终结果”的问题，但不能单独解决模型把伪工具调用当最终正文的问题。

### 3.2 增加子 Agent 专用完成质量门

通过 `AssistantCompletionGuard` 扩展点和子 Agent 专用的 `AgentCompletionGuard` 增加完成校验：

1. 普通、自包含的 `UNSPECIFIED` 回答仍然允许完成，兼容 OpenAI/Anthropic Adapter 的现有分类方式。
2. `UNSPECIFIED` 文本尾部出现本次故障特征（围栏外的 DSML、`</parameter>` 和 `invoke>` 闭合骨架）时拒绝完成。
3. 长文本中计划性语句严重重复时拒绝完成。
4. 校验失败时追加聚焦续跑提示，要求模型使用真实工具调用，或使用 `<final>...</final>` 返回最终答案。
5. 最多进行两次独立于 `maxSteps` 的质量恢复请求；连续三次不合格则返回 `MODEL_ERROR`，任务状态为 `FAILED`。
6. 被拒绝的长响应只保留最多 4,000 字符的首尾摘要，避免把约 40K 的退化文本再次完整送入模型。

协议尾部检测只作用于 `UNSPECIFIED`，且忽略三反引号代码围栏中的示例。通用 `<tool_call>` 文本不会触发该检测；明确的 `FINAL` 回答也可以正常讲解或引用协议标记，避免把合法技术报告误判为协议异常。

### 3.3 检测伪工具调用和协议不兼容

已在子 Agent 完成质量门中增加防御性检测：

- 当 Adapter 产出 `UNSPECIFIED`，且正文以本次观测到的围栏外 DSML 泄漏骨架结尾时，不直接作为最终答案接受。
- 首次和第二次异常会提示模型改用标准工具调用或返回明确最终答案。
- 第三次异常会以结构化 `MODEL_ERROR` 结束，而不是显示 `completed`。

应避免把某一种 Provider 私有的 DSML 格式直接实现为通用解析规则；更稳妥的方式是检测异常并重试标准协议。

### 3.4 增加重复输出保护

已增加轻量的输出退化检测：当长文本达到阈值，且 `Let me`、`I should`、`I'll now`、`接下来我`、`让我` 等计划性表达累计出现过多时，触发完成拒绝和聚焦重试。

### 3.5 后续可改进错误可观测性

TUI 目前仍只展示类似 `read_file failed` 的简略事件，没有直接展示失败路径和错误摘要。这不影响本次完成状态误判的修复，可作为后续独立改进。

建议在子 Agent 工具完成事件中补充：

- 工具名称。
- 关键输入摘要，例如读取的文件路径。
- 失败原因摘要，例如 `file not found`。
- 最终任务状态和失败工具之间的区别。

### 3.6 代码位置

- `src/main/java/minicode/core/loop/AssistantCompletionGuard.java`
- `src/main/java/minicode/core/loop/AgentLoop.java`
- `src/main/java/minicode/agent/runtime/AgentCompletionGuard.java`
- `src/main/java/minicode/agent/runtime/AgentRuntimeFactory.java`
- `src/main/java/minicode/agent/runtime/AgentRunResultMapper.java`

## 四、测试与验收标准

已补充以下测试：

1. `AgentRunResultMapperTest`
   - 过程消息加最终消息时，只返回最终消息。
   - 只有过程消息时，不返回成功结果。
2. `AgentCompletionGuardTest`
   - 普通 `UNSPECIFIED` 回答保持兼容。
   - 伪工具调用和长重复规划会被拒绝。
   - 明确 `FINAL` 中合法的工具协议示例不会被误判。
   - `UNSPECIFIED` 中合法的通用工具协议或围栏内 DSML 示例不会被误判。
3. `AgentLoopTest`
   - 完成质量重试拥有独立预算，即使 `maxSteps=1` 也会执行完整的有限重试。
4. `AgentRuntimeFactoryTest`
   - 异常完成后可以恢复并只返回最终报告。
   - 连续异常三次后发布 `FAILED`，而不是 `COMPLETED`。

完整回归命令为 `mvn test`。结果：共 618 项测试，0 失败、0 错误、3 跳过，构建成功。

验收标准：

- `completed` 表示子 Agent 已产生可交付的最终结果。
- 父 Agent 收到的结果不包含大段过程性规划。
- 伪工具调用、重复输出和空最终结果不会被误判为成功。
- 单个可恢复的工具错误不会错误地决定整个子 Agent 的最终状态。
