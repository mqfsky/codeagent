# CodeAgent 多 Agent 迁移规范（Mewcode 对齐版）

## 1. 目标

本次只迁移 Mewcode 的一次性子 Agent 能力，基于 CodeAgent 现有运行时重新实现，不增加持久化或跨进程可靠性功能。

包含：

- `explore`、`plan`、`general-purpose` 三种内置角色。
- 同步子 Agent。
- 后台子 Agent。
- 进程内任务状态、查询和取消 API。
- 子 Agent 完成后向父 Agent 投递完整结果。
- 父 Agent 空闲时自动继续一轮并处理后台结果。
- 同步子 Agent 的工具进度 UI。

不包含：

- Team、worktree、父会话 fork 和自定义角色。
- `model` 覆盖；子 Agent 继承当前 provider/model。
- 任务落盘、启动恢复、owner/lease、revision/CAS 和文件锁。
- 跨实例取消、崩溃后通知重投和 Session 通知去重。
- 并发/排队容量限制、全局任务超时和结果清理策略。
- 模型可见的 `task_list`、`task_status`、`task_output`、`task_cancel` 工具。

## 2. 公开行为

### 2.1 `agent` 工具

输入：

- 必填：`description`、`prompt`、`agent_type`。
- 可选：`run_in_background`，默认 `false`。
- `agent_type`：`explore`、`plan`、`general-purpose`。
- 三种角色都允许同步或后台执行。

同步模式阻塞父 Agent，结束后把完整结果作为本次工具结果返回。

后台模式立即返回：

```text
Agent "<description>" launched in background (task task_N). You will be notified when it completes.
```

### 2.2 内置角色

- `explore`：以只读代码调查为目标，允许用命令做只读检查，最多 30 steps。
- `plan`：以只读设计和实施计划为目标，允许用命令做只读检查，最多 15 steps。
- `general-purpose`：允许读取、写入和命令，最多 200 steps。
- 所有子 Agent 禁止 `agent`、`ask_user` 和名称以 `task_` 开头的工具，防止嵌套委派与交互。
- MCP 工具与 Mewcode 一样直接通过角色过滤，实际执行仍经过工具自身的权限链。

## 3. 运行时设计

### 3.1 子 Agent 隔离

- 子 Agent 只接收专用系统提示词和委派 `prompt`，不复制父会话历史。
- 每次运行创建独立 ToolRegistry、ContextManager、AutoCompactController 和 AgentLoop。
- Tool Schema 与实际执行 Registry 使用同一过滤结果。
- CodeAgent 的 provider Adapter 会按子 Registry fork；这是适配现有 Adapter 架构所需的内部实现，不增加产品能力。
- 子 Agent 不创建隐藏 Session，其内部消息不写入父 Session。

### 3.2 进程内后台任务

`SubAgentTaskManager` 使用：

- `LinkedHashMap` 保存当前进程任务。
- `ArrayList` 保存待投递通知。
- `AtomicInteger` 生成 `task_1`、`task_2` 等任务标识。
- Java 21 虚拟线程运行每个后台子 Agent。

任务状态只有：

```text
PENDING -> RUNNING -> COMPLETED
                   -> FAILED
                   -> CANCELLED
```

终态不会再被工作线程覆盖。该保护只用于避免进程内取消竞态，不提供跨进程一致性。

`getTask`、`listTasks`、`cancelTask` 仅为 Java 内部 API，不注册为模型工具。`cancelTask` 只能中断当前管理器保存的 `RUNNING` 线程。

### 3.3 通知

- 完成、失败或取消后，把任务状态和完整结果加入内存通知列表。
- `drainNotifications()` 返回列表副本后立即清空原列表。
- 通知包装为临时用户角色 `<system-reminder><task-notification>...</task-notification></system-reminder>` 提醒，在父 Agent 每个模型 step 前注入；不提升为 provider 顶层系统提示词。
- 通知不生成 Session 持久化动作；父 Agent 随后生成的汇总回答仍正常持久化。
- Renderer 与 MiniTui 中父 Agent 已空闲时，通知到达都会自动启动一次 continuation turn，无需用户再次输入。
- 进程崩溃、通知取出后模型失败或应用重启都可能丢失通知，这是与 Mewcode 一致的边界。

### 3.4 UI

- 同步子 Agent 可以显示角色生命周期和逐工具进度。
- 后台子 Agent 不转发逐工具进度，也不显示额外的 `pending/running` 任务块。
- 后台启动信息由 `agent` 工具结果展示；最终结果由父 Agent 自动续跑后汇总展示。
- Renderer 在权限交互结束后恢复弹窗前的输入状态，避免空闲后台任务的权限请求阻断最终通知。
- MiniTui 通过进程内通知回调启动 continuation turn，不增加任务轮询或持久化队列。
- MiniTui 的控制台输入由单一读取线程分流到聊天和权限提示，避免后台权限请求与主循环竞争同一输入流。

## 4. 验收标准

- 三种角色都能同步运行，也都能后台运行。
- 同步结果直接回到父 Agent 的 `agent` 工具结果。
- 后台调用立即返回 `task_N`，子 Agent 使用虚拟线程执行。
- 多个后台任务可以并行，不存在 4+16 容量限制。
- 子 Agent 达到角色最大 steps 时按失败处理，与 Mewcode 的 maximum iterations 错误语义一致。
- 任务和通知只存在于当前 `SubAgentTaskManager` 内存中，不创建 `~/.codeagent/agent-tasks`。
- `getTask/listTasks/cancelTask` 只操作当前实例。
- 通知包含完整结果，第一次 drain 后第二次为空。
- 父 Agent 活跃时在后续 model step 收到通知；空闲时 Renderer 和 MiniTui 自动启动 continuation turn。
- 通知本身不写 Session，父 Agent 的汇总回答正常写 Session。
- 父 Registry 只新增 `agent`，不存在模型可见的 `task_*` 工具。
- 后台任务不向 UI 转发子工具 started/completed 事件；同步任务仍可显示。
- OpenAI 和 Anthropic 的子 Agent Tool Schema 与过滤后的执行 Registry 一致。
- 完整 `mvn test` 保持通过。

## 5. 明确边界

- 这是单进程功能对齐版本，不以生产级后台任务系统为目标。
- 任务结果在进程退出后丢失属于预期行为。
- 通知采用最多一次的内存 drain，不承诺崩溃后的最终送达或 effectively-once。
- 后续若确实需要持久化、租约或可靠通知，应作为独立需求重新评估，不能默认并入本迁移。
