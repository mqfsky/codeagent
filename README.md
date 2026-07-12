# CodeAgent

<p align="center">
  <img src="./docs/logo.svg" alt="CodeAgent Logo" width="180" />
</p>

<h2 align="center">CodeAgent</h2>

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-D97757?style=for-the-badge" alt="Java 21" />
  <img src="https://img.shields.io/badge/CodeAgent-Java%20Edition-B85C3F?style=for-the-badge" alt="CodeAgent Edition" />
  <img src="https://img.shields.io/badge/terminal--first-agent-F0EBE1?style=for-the-badge&labelColor=8B8B8B" alt="terminal-first agent" />
</p>

---

<p align="center">
  A lightweight, local-first, terminal-first coding agent. CodeAgent.
</p>

[简体中文](./README_ZH.md) | [Improvement ideas (Chinese)](./IMPROVEMENTS_ZH.md)

CodeAgent is built for local development workflows: reading files, searching code, running commands, reviewing edits, keeping resumable sessions, and staying usable in long conversations through context compacting. It currently provides the minimum feature set of a coding agent. Many parts are still rough, but it runs.

## Features

- Anthropic-compatible and OpenAI-compatible provider paths
- terminal-first coding agent workflow
- built-in tools: file reading, search, edit, write, command execution, `ask_user`, and `load_skill`
- permission review before sensitive actions
- append-only JSONL sessions with `list`, `rename`, `resume`, and `fork`
- manual `/compact` and full autoCompact
- Windows launcher and runnable fat jar
- This branch is entirely implemented in Java, while the `default-ts-ui` branch features a more visually appealing TypeScript version of the TUI.

## Build

Requires:

- JDK 21
- Maven 3.9+
- PowerShell

If `java` and `mvn` are already available in `PATH`:

```powershell
cd <CodeAgent source directory>

java -version
mvn -version

mvn test
mvn package
```

Outputs:

```text
target\codeagent.jar
target\dist\codeagent\
```

Where:

- `target\codeagent.jar` is a runnable fat jar.
- `target\dist\codeagent\` is the distribution directory containing `bin` and `lib`.

After building, check the version and help output:

```powershell
java -jar target\codeagent.jar --version
java -jar target\codeagent.jar --help
```

You can also test the Windows launcher in the distribution directory:

```powershell
target\dist\codeagent\bin\codeagent.cmd --version
target\dist\codeagent\bin\codeagent.cmd --help
```

If JDK 21 is not in `PATH`, set it for the current PowerShell session:

```powershell
cd <CodeAgent source directory>

$env:JAVA_HOME="<your JDK 21 installation directory>"
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"

mvn test
mvn package
```

## Run

Add the distribution `bin` directory to `PATH`:

```powershell
$env:PATH="<CodeAgent source directory>\target\dist\codeagent\bin;$env:PATH"
```

Then start CodeAgent from any project directory. Make sure `JAVA_HOME` is configured:

```powershell
cd <your project directory>
codeagent
```

The default workspace is the current shell directory. You can also pass it explicitly:

```powershell
codeagent --cwd <your project directory>
```

Without changing `PATH`, run the launcher by full path:

```powershell
cd <your project directory>
<CodeAgent source directory>\target\dist\codeagent\bin\codeagent.cmd
```

Quick smoke test:

```powershell
New-Item -ItemType Directory -Force .\manual-test-workspace | Out-Null
cd .\manual-test-workspace
<CodeAgent source directory>\target\dist\codeagent\bin\codeagent.cmd --version
<CodeAgent source directory>\target\dist\codeagent\bin\codeagent.cmd session list
```

## Provider

Example Anthropic-compatible or OpenAI-compatible endpoint:

```powershell
$env:CODEAGENT_PROVIDER="anthropic-compatible"
$env:ANTHROPIC_BASE_URL="https://api.xiaomimimo.com/anthropic"
$env:ANTHROPIC_MODEL="mimo-v2.5-pro"
$env:ANTHROPIC_AUTH_TOKEN="<token>"
```

Do not commit or print real tokens.

## Commands

```powershell
codeagent
codeagent --cwd <path>
codeagent --resume <id>
codeagent --fork <id>
codeagent session list
codeagent session rename <id> <title>
codeagent --max-steps <n>
codeagent --version
codeagent --help
```

Inside either interactive TUI, use `/init` to generate `CODEAGENT.md` and `.codeagent/rules/`,
`/memory` to inspect the memory files loaded into the prompt, and `/compact` to compress the session context.

Sessions are scoped by workspace cwd. To resume a session, run from the same project directory or pass the same `--cwd`.
