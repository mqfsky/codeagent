# CodeAgent

CodeAgent 是一个使用 Java 21 编写的、本地优先个人助手，软件工程是它的主要能力。

它围绕一条完整的 `模型 -> 工具 -> 模型` 执行链工作：理解用户任务，读取和搜索代码，调用本地工具完成修改或验证，再把工具结果交还给模型继续判断。同时，CodeAgent 提供权限确认、可恢复会话、上下文压缩、项目记忆、Skills 和 MCP 扩展能力。

> 当前项目主要用于学习、实验和验证 Coding Agent 的核心运行机制，尚未按生产级产品标准完善。请在版本控制环境中使用，并在提交前检查 Agent 产生的修改。

## 主要能力

- 支持 Anthropic-compatible 和 OpenAI-compatible 模型服务。
- 提供文件读取、目录遍历、文本搜索、文件写入、精确编辑、批量补丁和命令执行工具。
- 在路径访问、命令执行、文件修改和 MCP 工具调用前进行权限检查。
- 使用 append-only JSONL 保存会话，支持列出、重命名、恢复和分叉会话。
- 支持手动 `/compact` 和自动上下文压缩，避免长对话无限膨胀。
- 支持 `CODEAGENT.md`、`AGENTS.md` 和 `.codeagent/rules/*.md` 分层项目记忆。
- 支持从项目级、用户级和兼容目录发现 `SKILL.md`，按需加载完整 Skill。
- 支持通过 stdio 或 Streamable HTTP 连接 MCP Server，并把远端能力注册为 Agent 工具。
- 可选接入个人飞书主日历，以强类型工具创建私密日程，并在每次外部写入前展示确认信息。
- 提供全屏 Renderer TUI；终端能力不足时自动回退到普通行模式。

## 工作流程

```text
用户输入
  -> MiniTui / RendererTuiShell
  -> ApplicationServices
  -> AgentLoop
       -> ModelAdapter 生成回复或工具调用
       -> ToolRegistry 校验并执行工具
       -> PermissionService 处理敏感操作授权
       -> ContextManager 控制工具结果和上下文体积
  -> SessionPersistenceRunner
  -> SessionStore 追加写入 JSONL
```

普通自然语言任务由模型理解；`/compact`、`/memory`、`/init`、`/skill` 等本地命令由 TUI 直接识别，不会进入模型调用链。

## 环境要求

- JDK 21
- Maven 3.9+

检查本地环境：

```bash
java -version
mvn -version
```

## 构建

在 CodeAgent 源码目录执行：

```bash
mvn test
mvn package
```

主要构建产物：

```text
target/codeagent.jar
target/dist/codeagent/lib/codeagent.jar
```

`target/codeagent.jar` 是包含运行依赖的 fat jar，可以直接启动：

```bash
java -jar target/codeagent.jar --version
java -jar target/codeagent.jar --help
```

## 快速开始

### 1. 配置模型

推荐把个人模型配置写入：

```text
~/.codeagent/settings.json
```

OpenAI-compatible 示例：

```json
{
  "provider": "openai-compatible",
  "model": "your-model",
  "baseUrl": "https://your-provider.example/v1",
  "apiKey": "your-api-key"
}
```

Anthropic-compatible 示例：

```json
{
  "provider": "anthropic-compatible",
  "model": "your-model",
  "baseUrl": "https://your-provider.example",
  "authToken": "your-auth-token"
}
```

支持的 `provider` 值：

| 配置值 | 说明 |
| --- | --- |
| `anthropic`、`anthropic-compatible` | 使用 Anthropic Messages API 兼容协议 |
| `openai`、`openai-compatible` | 使用 OpenAI Chat Completions 兼容协议 |
| `mock` | 本地测试模式，不请求真实模型服务 |

也可以使用环境变量：

```bash
export CODEAGENT_PROVIDER="openai-compatible"
export CODEAGENT_MODEL="your-model"
export ANTHROPIC_BASE_URL="https://your-provider.example/v1"
export ANTHROPIC_API_KEY="your-api-key"
```

当前支持的主要配置项包括：

- `provider`：模型服务类型。
- `model`：模型名称。
- `baseUrl`：模型服务地址。
- `apiKey` / `authToken`：鉴权信息。
- `maxOutputTokens`：单次模型输出上限。
- `contextWindow`：模型上下文窗口大小。
- `maxSteps`：单轮 Agent 最大执行步数。
- `providerTimeoutSeconds`：模型请求超时时间，默认 300 秒。
- `mcpServers`：MCP Server 配置。
- `integrations.feishuCalendar`：用户级飞书日历创建工具配置。

配置加载优先级为：

```text
环境变量
  > 当前项目 .codeagent/settings.json
  > 用户目录 ~/.codeagent/settings.json
  > 内置默认值
```

建议把 API Key 放在用户级配置或环境变量中，不要把真实密钥提交到 Git。

### 2. 配置飞书日历（可选）

飞书日历集成只从用户级 `~/.codeagent/settings.json` 读取，项目配置不能覆盖个人身份、CLI 路径或目标日历：

```json
{
  "integrations": {
    "feishuCalendar": {
      "enabled": true,
      "cliPath": "/opt/homebrew/bin/lark-cli",
      "timezone": "Asia/Shanghai",
      "defaultDurationMinutes": 30,
      "defaultReminderMinutes": 5,
      "timeoutSeconds": 30
    }
  }
}
```

该功能固定使用当前用户的飞书主日历和 `--as user` 身份，不接受模型提供的 `calendarId`、时区、身份或原始 CLI 参数。每次创建前都会展示标题、起止时间、时区和提醒信息，只支持单次允许，不支持永久放行。

飞书应用至少需要以下用户权限：

```text
calendar:calendar:readonly
calendar:calendar.event:create
offline_access
```

授权示例：

```bash
/opt/homebrew/bin/lark-cli auth login \
  --scope "calendar:calendar:readonly calendar:calendar.event:create offline_access" \
  --no-wait \
  --json
```

用户完成 split-flow 登录后，需要用明确的创建指令，例如：

```text
帮我创建日程：明天九点看八股文
创建飞书日程：后天晚上八点复习数据库
添加到飞书日历：9月10日下午三点整理面试笔记
```

普通的计划或意图陈述（例如“明天九点我要看八股文”或“明天要复习项目，刷算法，完善简历”）不会创建日程，也不会触发日程信息补问。只有明确要求创建、添加或安排日程时才会进入创建流程；进入流程后，对 Agent 补问的直接回答不必重复“创建日程”。如果一次创建指令包含多个活动且无法判断应合并还是拆分，Agent 会先询问。

v1 支持今天、明天、后天和月日表达，以及上午、下午、晚上、凌晨和裸 24 小时时刻。明确创建请求缺少标题、日期或具体时间时会先询问；暂不支持重复日程、参与人、修改、删除及“下周一”“月底”等扩展表达。

### 3. 在目标项目中启动

进入希望 Agent 操作的项目目录，然后使用 CodeAgent 的绝对路径启动：

```bash
cd /path/to/your/project
java -jar /path/to/codeagent/target/codeagent.jar
```

也可以显式指定 workspace：

```bash
java -jar /path/to/codeagent/target/codeagent.jar --cwd /path/to/your/project
```

启动后可以直接输入自然语言任务，例如：

```text
解释一下这个项目的启动流程
修复当前失败的单元测试
给这个接口增加参数校验，并补充测试
```

## 命令行参数

下表使用 `codeagent` 作为命令名简写。如果本地没有配置 launcher，请将它替换为 `java -jar /path/to/codeagent/target/codeagent.jar`。

| 命令 | 作用 |
| --- | --- |
| `codeagent` | 在当前目录创建新会话 |
| `codeagent --cwd <path>` | 指定 workspace |
| `codeagent --resume <id>` | 恢复当前 workspace 下的会话 |
| `codeagent --fork <id>` | 基于已有会话历史创建新会话 |
| `codeagent session list` | 列出当前 workspace 的会话 |
| `codeagent session rename <id> <title>` | 重命名会话 |
| `codeagent --max-steps <n>` | 设置单轮最大步骤数，范围为 1 到 100 |
| `codeagent --version` | 显示版本 |
| `codeagent --help` | 显示帮助 |

例如：

```bash
java -jar target/codeagent.jar session list
java -jar target/codeagent.jar --resume <session-id>
```

## 对话内命令

以下命令由 TUI 本地处理，不会作为普通用户消息发送给模型：

| 命令 | 作用 |
| --- | --- |
| `/init` | 检测项目结构，生成 `CODEAGENT.md` 和 `.codeagent/rules/*.md` |
| `/memory` | 查看当前会注入系统提示词的项目记忆文件 |
| `/skill` | 列出本次启动时发现的 Skills |
| `/compact` | 手动压缩当前会话上下文 |
| `exit`、`quit` | 退出 CodeAgent |

## 会话与恢复

CodeAgent 把会话保存为 append-only JSONL。用户消息、模型回复、工具调用、工具结果、压缩边界和会话元数据都会作为事件追加保存，而不是反复覆盖整个文件。

会话按 workspace 的绝对路径隔离，默认存放在：

```text
~/.codeagent/sessions/
```

因此，恢复会话时需要回到原来的项目目录，或者传入相同的 `--cwd`：

```bash
java -jar target/codeagent.jar --cwd /path/to/project --resume <session-id>
```

`--fork` 会读取源会话最近一次压缩边界之后的可恢复历史，为新会话写入 fork 元数据，并生成新的 session ID。

## 项目记忆

项目记忆是写在 Markdown 文件中的长期项目说明。CodeAgent 会在每一轮模型请求前重新加载这些文件，并把它们加入系统提示词；它不会因为用户说了“记住这件事”就自动修改记忆文件。

在项目中输入：

```text
/init
```

CodeAgent 会检测 Java、Maven 和 Gradle 项目结构，并在文件不存在时生成其中适用的文件：

```text
CODEAGENT.md
.codeagent/
└── rules/
    ├── project.md
    ├── java.md      # 检测到 Java 时生成
    ├── maven.md     # 检测到 Maven 时生成
    └── gradle.md    # 检测到 Gradle 时生成
```

已经存在的文件不会被覆盖。生成后应根据项目实际情况调整其中的构建命令、代码规范和验证要求。

CodeAgent 还兼容 `AGENTS.md`、目录级本地规则以及 `.mini-code/rules/*.md`。记忆文件支持通过单独一行 `@relative/path.md` 引用同一安全边界内的其他 Markdown 文件。

## Skills

一个 Skill 是一个包含工作流说明的 `SKILL.md`。启动时，CodeAgent 只把 Skill 名称和简介加入系统提示词；当任务匹配某个 Skill 时，模型通过 `load_skill` 读取完整内容。

推荐的项目级目录结构：

```text
.codeagent/
└── skills/
    └── code-review/
        └── SKILL.md
```

示例 `SKILL.md`：

```markdown
---
description: 审查 Java 修改并检查测试、异常处理和资源释放。
---

# Code Review

1. 先检查修改范围和调用链。
2. 再检查行为变化是否有测试覆盖。
3. 最后运行与改动相关的验证命令。
```

Skill 名称取自目录名，上面的 Skill 名称是 `code-review`。

发现顺序如下，同名 Skill 只保留优先级更高的第一个：

1. `<workspace>/.codeagent/skills/`
2. `~/.codeagent/skills/`
3. `<workspace>/.mini-code/skills/`
4. `~/.mini-code/skills/`
5. `<workspace>/.claude/skills/`
6. `~/.claude/skills/`

新增或修改 Skill 后需要重新启动 CodeAgent。进入对话后可以输入 `/skill` 查看本次启动实际发现的列表。

## MCP

CodeAgent 支持 stdio 和 Streamable HTTP 两种 MCP 传输。可以在用户级或项目级 `settings.json` 中配置；每个启用的 Server 必须在 `command` 与 `url` 中二选一。

stdio 示例：

```json
{
  "mcpServers": {
    "example": {
      "command": "node",
      "args": ["/absolute/path/to/mcp-server.js"],
      "cwd": ".",
      "env": {
        "EXAMPLE_ENV": "value"
      },
      "enabled": true
    }
  }
}
```

Streamable HTTP 示例，其中 `url` 是完整的 MCP endpoint，不会自动拼接 `/mcp`：

```json
{
  "mcpServers": {
    "remote": {
      "url": "https://example.com/mcp",
      "headers": {
        "Authorization": "Bearer ${MCP_TOKEN}"
      },
      "enabled": true
    }
  }
}
```

项目级配置会覆盖用户级同名 Server 的字段；同一传输内的 `env` 或 `headers` 会按键合并。项目级同名 Server 从 `command` 切换到 `url`（或反向切换）时，会清除继承自另一种传输的字段。Header 值支持 `${ENV_NAME}` 环境变量插值；缺失变量或试图覆盖 MCP 协议保留 Header 时，只会让对应 Server 启动失败，Header 内容不会进入摘要或系统提示词。

启动时，CodeAgent 会完成 MCP 初始化、读取 Server Instructions 和工具列表，并把工具注册为类似 `mcp__example__tool_name` 的名称。Server Instructions 会作为不可信远端内容按 Server 分区、限长后加入每轮系统提示词，不能覆盖用户指令、安全规则或权限决定。单个 Server 启动失败时会记录错误状态，不会阻止其他 Server 继续初始化。

stdio 现在遵循 MCP 标准，使用单行 JSON-RPC（换行分隔）通信。若私有 MCP Server 依赖 CodeAgent 旧版非标准 `Content-Length` framing，需要同步改为换行分隔 JSON。

MCP 工具调用同样经过权限检查。

## 权限与安全边界

模型输出被视为不可信输入。涉及路径、命令、编辑和 MCP 的操作会先经过权限服务，用户可以选择：

- 仅允许一次。
- 在当前 turn 内允许。
- 始终允许。
- 仅拒绝一次。
- 始终拒绝。
- 拒绝并向 Agent 提供反馈。

“始终允许”和“始终拒绝”的决策会保存到：

```text
~/.codeagent/permissions.json
```

命令工具优先使用显式参数数组，并会拒绝没有经过支持的 shell 片段。即便如此，仍建议：

- 在 Git 仓库或可恢复的工作副本中运行 CodeAgent。
- 执行前认真检查高风险权限请求。
- 提交前审阅 diff 并运行项目测试。
- 不在 Prompt、日志或仓库配置中暴露真实密钥。

## 本地数据目录

CodeAgent 默认把运行数据放在 `~/.codeagent/`：

```text
~/.codeagent/
├── settings.json       # 用户级模型和 MCP 配置
├── permissions.json    # 持久化权限决策
├── sessions/           # 按 workspace 隔离的 JSONL 会话
├── skills/             # 用户级 Skills
└── tool-results/       # 被上下文管理器外置的大型工具结果
```

项目级配置和规则放在目标 workspace 中：

```text
<workspace>/
├── CODEAGENT.md
└── .codeagent/
    ├── settings.json
    ├── rules/
    └── skills/
```

## 源码结构

核心代码位于 `src/main/java/minicode/`：

| 目录 | 职责 |
| --- | --- |
| `app` | 参数解析、配置加载和应用装配 |
| `tui` | 普通行模式、Renderer TUI 和终端事件展示 |
| `core` | AgentLoop、消息、步骤、turn 和运行事件 |
| `model` | Anthropic、OpenAI-compatible 和 Mock 模型适配器 |
| `tools` | 工具接口、注册表、内置工具和结果处理 |
| `permissions` | 权限请求、作用域、持久化决策和用户交互 |
| `session` | JSONL 会话存储、恢复、重命名和 fork |
| `context` | token 统计、大工具结果管理和上下文压缩 |
| `memory`、`init` | 分层项目记忆加载和初始化 |
| `skills` | Skill 发现、摘要注册和按需加载 |
| `mcp` | stdio / Streamable HTTP MCP Client、工具发现和运行时管理 |

## 开发与验证

运行完整测试：

```bash
mvn test
```

构建可运行 jar：

```bash
mvn package
```

改动 Agent 行为时，建议至少同时验证：

- 普通文本回复是否能正确结束 turn。
- 工具调用结果是否能回到下一步模型上下文。
- 权限允许、拒绝和持久化是否符合预期。
- session 是否能恢复，compact 边界是否正确。
- Renderer TUI 与普通行模式是否保持一致。

## 当前定位

CodeAgent 已经覆盖 Coding Agent 的核心闭环，但仍是一个持续迭代中的个人项目。当前更适合用于：

- 学习 Agent Loop、工具调用和上下文管理。
- 验证模型适配、权限系统、会话持久化、Skills 与 MCP 设计。
- 在受控代码仓库中完成小规模开发任务。

在用于重要或生产环境之前，仍需要补强评测体系、可观测性、故障恢复、跨平台发布和更严格的安全策略。
