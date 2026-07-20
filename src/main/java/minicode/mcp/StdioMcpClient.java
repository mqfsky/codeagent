package minicode.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 通过子进程 stdin/stdout 传输换行分隔 JSON-RPC 消息的 MCP 客户端。
 */
public final class StdioMcpClient extends AbstractMcpClient {
    private static final Set<String> SUPPORTED_PROTOCOL_VERSIONS = Set.of(
            PROTOCOL_VERSION_2024_11_05,
            PROTOCOL_VERSION_2025_03_26,
            PROTOCOL_VERSION_2025_06_18,
            PROTOCOL_VERSION_2025_11_25
    );

    private final McpServerConfig config;
    private final Path baseCwd;
    private Process process;
    private ProcessHandle lastProcessHandle;
    private BufferedReader stdout;
    private BufferedWriter stdin;

    public StdioMcpClient(String serverName, McpServerConfig config) {
        this(serverName, config, Path.of(".").toAbsolutePath().normalize());
    }

    public StdioMcpClient(String serverName, McpServerConfig config, Path baseCwd) {
        super(serverName, PROTOCOL_VERSION_2024_11_05, SUPPORTED_PROTOCOL_VERSIONS);
        this.config = Objects.requireNonNull(config, "config");
        this.baseCwd = Objects.requireNonNull(baseCwd, "baseCwd").toAbsolutePath().normalize();
    }

    @Override
    protected synchronized void openTransport() {
        if (process != null && process.isAlive()) {
            return;
        }
        spawnProcess();
    }

    @Override
    protected JsonNode exchange(ObjectNode message, Duration timeout, String method, McpErrorKind failureKind) {
        writeMessage(message);
        return readMessageWithTimeout(timeout, method, failureKind);
    }

    @Override
    protected void sendNotification(ObjectNode message, Duration timeout, String method,
                                    McpErrorKind failureKind) {
        writeMessage(message);
    }

    @Override
    protected Duration initializeTimeout() {
        return config.initializeTimeout();
    }

    @Override
    protected Duration requestTimeout() {
        return config.callTimeout();
    }

    public synchronized Optional<ProcessHandle> processHandle() {
        if (process != null) {
            lastProcessHandle = process.toHandle();
            return Optional.of(lastProcessHandle);
        }
        return Optional.ofNullable(lastProcessHandle);
    }

    @Override
    public synchronized void close() {
        Process current = process;
        if (current == null) {
            resetInitialization();
            return;
        }
        closeQuietly(stdin);
        try {
            if (!current.waitFor(1, TimeUnit.SECONDS)) {
                current.destroy();
            }
            if (!current.waitFor(1, TimeUnit.SECONDS)) {
                current.destroyForcibly();
                current.waitFor(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            current.destroyForcibly();
        } finally {
            // 先结束进程再关闭 BufferedReader，避免与正在 readLine 的线程争用 Reader 锁。
            closeQuietly(stdout);
            closeQuietly(current.getErrorStream());
            process = null;
            stdin = null;
            stdout = null;
            resetInitialization();
        }
    }

    private void spawnProcess() {
        String command = config.command();
        if (command.isBlank()) {
            throw new McpException(McpErrorKind.START_FAILED,
                    "MCP server \"" + serverName() + "\" has no command configured.");
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.command().addAll(config.args());
        builder.directory(config.cwd()
                .map(cwd -> baseCwd.resolve(cwd).toAbsolutePath().normalize())
                .orElse(baseCwd)
                .toFile());
        builder.environment().putAll(config.env());
        // MCP Server 可以把日志写到 stderr；丢弃它以免无人消费的 pipe 填满并阻塞 Server。
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        try {
            process = builder.start();
            lastProcessHandle = process.toHandle();
            stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new McpException(McpErrorKind.START_FAILED,
                    "Failed to start MCP server \"" + serverName() + "\" using command \"" + command + "\"."
                            + startFailureDetail(command, exception),
                    exception);
        }
    }

    private static String startFailureDetail(String command, IOException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage();
        if (message.contains("CreateProcess error=2") || message.toLowerCase(Locale.ROOT).contains("no such file")) {
            return "\nCommand not found: " + command + ". Install it first and ensure it is available in PATH.";
        }
        return message.isBlank() ? "" : "\n" + message;
    }

    private synchronized void writeMessage(JsonNode message) {
        ensureRunning();
        try {
            // MCP stdio 使用一行一条紧凑 JSON-RPC 消息，不使用 HTTP 风格 Content-Length framing。
            stdin.write(JSON.writeValueAsString(message));
            stdin.write('\n');
            stdin.flush();
        } catch (IOException exception) {
            throw new McpException(McpErrorKind.PROCESS_EXITED,
                    "MCP server \"" + serverName() + "\" closed while writing request.", exception);
        }
    }

    private JsonNode readMessageWithTimeout(Duration timeout, String method, McpErrorKind failureKind) {
        CompletableFuture<JsonNode> future = CompletableFuture.supplyAsync(() -> readMessage(method));
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            abortTransport();
            throw new McpException(McpErrorKind.TIMEOUT,
                    "MCP " + serverName() + ": request timed out for " + method, exception);
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new McpException(failureKind,
                    "MCP " + serverName() + ": interrupted while waiting for " + method, exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            if (cause instanceof McpException mcpException) {
                throw mcpException;
            }
            throw new McpException(failureKind,
                    "MCP " + serverName() + ": request failed for " + method, cause);
        }
    }

    private JsonNode readMessage(String method) {
        ensureRunning();
        try {
            String line = stdout.readLine();
            if (line == null) {
                throw new McpException(McpErrorKind.PROCESS_EXITED,
                        "MCP server \"" + serverName() + "\" closed before completing " + method + ".");
            }
            try {
                return JSON.reader()
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .readTree(line);
            } catch (JsonProcessingException exception) {
                throw new McpException(McpErrorKind.PROTOCOL_ERROR,
                        "MCP " + serverName() + ": stdout contained a non-JSON message for " + method + ".",
                        exception);
            }
        } catch (McpException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new McpException(McpErrorKind.PROTOCOL_ERROR,
                    "MCP " + serverName() + ": failed to read response for " + method + ".", exception);
        }
    }

    private void ensureRunning() {
        if (process == null || !process.isAlive() || stdin == null || stdout == null) {
            throw new McpException(McpErrorKind.PROCESS_EXITED,
                    "MCP server \"" + serverName() + "\" is not running.");
        }
    }

    private synchronized void abortTransport() {
        Process current = process;
        if (current == null) {
            resetInitialization();
            return;
        }
        closeQuietly(stdin);
        current.destroyForcibly();
        try {
            current.waitFor(1, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            closeQuietly(stdout);
            closeQuietly(current.getErrorStream());
            process = null;
            stdin = null;
            stdout = null;
            resetInitialization();
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }
}
