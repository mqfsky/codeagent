package minicode.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * MCP Streamable HTTP transport implemented with the JDK HTTP client.
 *
 * <p>Each JSON-RPC message is sent as a new POST to the configured endpoint. Responses may be a
 * single JSON document or an SSE stream. This transport deliberately does not implement the
 * optional standalone GET stream.</p>
 */
public final class StreamableHttpMcpClient extends AbstractMcpClient {
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String ACCEPT = "Accept";
    private static final String SESSION_ID = "MCP-Session-Id";
    private static final String PROTOCOL_VERSION = "MCP-Protocol-Version";
    private static final String JSON_MEDIA_TYPE = "application/json";
    private static final String SSE_MEDIA_TYPE = "text/event-stream";
    private static final String ACCEPT_VALUE = JSON_MEDIA_TYPE + ", " + SSE_MEDIA_TYPE;
    private static final Set<String> SUPPORTED_PROTOCOL_VERSIONS = Set.of(
            PROTOCOL_VERSION_2025_11_25,
            PROTOCOL_VERSION_2025_06_18,
            PROTOCOL_VERSION_2025_03_26
    );
    private static final Set<String> RESERVED_HEADERS = Set.of(
            "accept",
            "connection",
            "content-length",
            "content-type",
            "expect",
            "host",
            "mcp-protocol-version",
            "mcp-session-id",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade"
    );

    private final McpServerConfig config;
    private URI endpoint;
    private HttpClient httpClient;
    private String sessionId;
    private String negotiatedProtocolVersion;
    private boolean recoveringNotificationSession;

    public StreamableHttpMcpClient(String serverName, McpServerConfig config) {
        super(serverName, PROTOCOL_VERSION_2025_11_25, SUPPORTED_PROTOCOL_VERSIONS);
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    protected void openTransport() {
        validateConfiguration();
        endpoint = URI.create(config.url().orElseThrow());
        httpClient = HttpClient.newBuilder()
                .connectTimeout(config.initializeTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        sessionId = null;
        negotiatedProtocolVersion = null;
        recoveringNotificationSession = false;
    }

    @Override
    protected JsonNode exchange(ObjectNode message, Duration timeout, String method,
                                McpErrorKind failureKind) {
        long responseDeadline = System.nanoTime() + timeout.toNanos();
        HttpResponse<InputStream> response = post(message, timeout, method, failureKind);
        boolean canReinitialize = !"initialize".equals(method) && sessionId != null;
        if (response.statusCode() == 404 && canReinitialize) {
            closeBody(response);
            sessionId = null;
            negotiatedProtocolVersion = null;
            reinitialize();
            responseDeadline = System.nanoTime() + timeout.toNanos();
            response = post(message, timeout, method, failureKind);
        }
        HttpResponse<InputStream> selectedResponse = response;
        requireSuccessfulStatus(selectedResponse, method);

        if ("initialize".equals(method)) {
            sessionId = readSessionId(selectedResponse, method);
        }

        String mediaType = mediaType(selectedResponse.headers()).orElseThrow(() -> {
            closeBody(selectedResponse);
            return protocolError(method, "response is missing Content-Type");
        });
        Duration remaining = remaining(responseDeadline);
        return switch (mediaType) {
            case JSON_MEDIA_TYPE -> parseWithTimeout(
                    selectedResponse.body(), remaining, method,
                    () -> parseJson(selectedResponse.body(), method));
            case SSE_MEDIA_TYPE -> parseWithTimeout(
                    selectedResponse.body(), remaining, method,
                    () -> parseSse(selectedResponse.body(), message.get("id"), method));
            default -> {
                closeBody(selectedResponse);
                throw protocolError(method, "unsupported response Content-Type " + mediaType);
            }
        };
    }

    @Override
    protected void sendNotification(ObjectNode message, Duration timeout, String method,
                                    McpErrorKind failureKind) {
        long responseDeadline = System.nanoTime() + timeout.toNanos();
        HttpResponse<InputStream> response = post(message, timeout, method, failureKind);
        if (response.statusCode() == 404 && sessionId != null && !recoveringNotificationSession) {
            closeBody(response);
            sessionId = null;
            negotiatedProtocolVersion = null;
            recoveringNotificationSession = true;
            try {
                // The replacement lifecycle sends its own initialized notification, so the stale
                // notification must not be posted again after recovery.
                reinitialize();
            } finally {
                recoveringNotificationSession = false;
            }
            return;
        }
        requireSuccessfulStatus(response, method);
        byte[] body = parseWithTimeout(response.body(), remaining(responseDeadline), method,
                () -> readAllBytes(response.body()));
        if (!new String(body, StandardCharsets.UTF_8).isBlank()) {
            throw protocolError(method, "notification response body must be empty");
        }
    }

    @Override
    protected Duration initializeTimeout() {
        return config.initializeTimeout();
    }

    @Override
    protected Duration requestTimeout() {
        return config.requestTimeout();
    }

    @Override
    protected void onInitialized(McpInitialization initialized) {
        negotiatedProtocolVersion = initialized.protocolVersion();
    }

    @Override
    public synchronized void close() {
        String closingSession = sessionId;
        sessionId = null;
        if (closingSession != null && httpClient != null && endpoint != null) {
            bestEffortDelete(closingSession);
        }
        negotiatedProtocolVersion = null;
        httpClient = null;
        endpoint = null;
        resetInitialization();
    }

    private HttpResponse<InputStream> post(ObjectNode message, Duration timeout, String method,
                                           McpErrorKind failureKind) {
        ensureTransportOpen();
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(message.toString(), StandardCharsets.UTF_8));
        addStaticHeaders(builder);
        builder.header(CONTENT_TYPE, JSON_MEDIA_TYPE);
        builder.header(ACCEPT, ACCEPT_VALUE);
        if (!"initialize".equals(method) && negotiatedProtocolVersion != null) {
            builder.header(PROTOCOL_VERSION, negotiatedProtocolVersion);
        }
        if (!"initialize".equals(method) && sessionId != null) {
            builder.header(SESSION_ID, sessionId);
        }

        try {
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException exception) {
            throw timeout(method, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new McpException(failureKind,
                    "MCP " + serverName() + ": HTTP request was interrupted for " + method + ".",
                    exception);
        } catch (IOException exception) {
            if ("initialize".equals(method) && isConnectionFailure(exception)) {
                throw new McpException(McpErrorKind.START_FAILED,
                        "MCP " + serverName() + ": failed to connect to the HTTP endpoint.", exception);
            }
            throw new McpException(failureKind,
                    "MCP " + serverName() + ": HTTP transport failed for " + method + ".", exception);
        }
    }

    private void bestEffortDelete(String closingSession) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                    .timeout(config.requestTimeout())
                    .DELETE();
            addStaticHeaders(builder);
            builder.header(ACCEPT, ACCEPT_VALUE);
            builder.header(SESSION_ID, closingSession);
            if (negotiatedProtocolVersion != null) {
                builder.header(PROTOCOL_VERSION, negotiatedProtocolVersion);
            }
            httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding());
        } catch (IOException | InterruptedException | RuntimeException ignored) {
            if (ignored instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void validateConfiguration() {
        if (config.transportKind() != McpServerConfig.TransportKind.STREAMABLE_HTTP
                || config.url().isEmpty()) {
            throw new McpException(McpErrorKind.START_FAILED,
                    "MCP server \"" + serverName() + "\" has an invalid Streamable HTTP endpoint.");
        }
        for (Map.Entry<String, String> header : config.headers().entrySet()) {
            String name = header.getKey();
            String value = header.getValue();
            if (name == null || name.isBlank()) {
                throw invalidHeader();
            }
            if (RESERVED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                throw invalidHeader();
            }
            if (value == null || containsUnexpandedEnvironmentVariable(value)) {
                throw invalidHeader();
            }
            try {
                HttpRequest.newBuilder(URI.create("http://localhost"))
                        .header(name, value);
            } catch (IllegalArgumentException exception) {
                throw invalidHeader();
            }
        }
    }

    private void addStaticHeaders(HttpRequest.Builder builder) {
        for (Map.Entry<String, String> header : config.headers().entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }
    }

    private static boolean containsUnexpandedEnvironmentVariable(String value) {
        int start = value.indexOf("${");
        return start >= 0 && value.indexOf('}', start + 2) >= 0;
    }

    private McpException invalidHeader() {
        return new McpException(McpErrorKind.START_FAILED,
                "MCP server \"" + serverName() + "\" has an invalid or reserved HTTP header.");
    }

    private String readSessionId(HttpResponse<InputStream> response, String method) {
        Optional<String> value = response.headers().firstValue(SESSION_ID);
        if (value.isEmpty()) {
            return null;
        }
        String session = value.orElseThrow();
        boolean visibleAscii = !session.isEmpty()
                && session.chars().allMatch(character -> character >= 0x21 && character <= 0x7e);
        if (!visibleAscii) {
            closeBody(response);
            throw protocolError(method, "response has an invalid session identifier");
        }
        return session;
    }

    private void ensureTransportOpen() {
        if (httpClient == null || endpoint == null) {
            throw new McpException(McpErrorKind.START_FAILED,
                    "MCP server \"" + serverName() + "\" HTTP transport is not open.");
        }
    }

    private void requireSuccessfulStatus(HttpResponse<InputStream> response, String method) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return;
        }
        closeBody(response);
        throw new McpException(McpErrorKind.REQUEST_FAILED,
                "MCP " + serverName() + ": HTTP " + status + " for " + method + ".");
    }

    private Optional<String> mediaType(HttpHeaders headers) {
        return headers.firstValue(CONTENT_TYPE)
                .map(value -> value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isEmpty());
    }

    private JsonNode parseJson(InputStream body, String method) throws IOException {
        try (body) {
            JsonNode parsed = JSON.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(body);
            if (parsed == null) {
                throw protocolError(method, "JSON response body is empty");
            }
            return parsed;
        } catch (JsonProcessingException exception) {
            throw protocolError(method, "response body is not valid JSON", exception);
        }
    }

    private byte[] readAllBytes(InputStream body) throws IOException {
        try (body) {
            return body.readAllBytes();
        }
    }

    private JsonNode parseSse(InputStream body, JsonNode expectedId, String method) throws IOException {
        try (body;
             BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            List<String> dataLines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    JsonNode matching = parseSseEvent(dataLines, expectedId, method);
                    dataLines.clear();
                    if (matching != null) {
                        return matching;
                    }
                    continue;
                }
                if (line.startsWith(":")) {
                    continue;
                }
                int colon = line.indexOf(':');
                String field = colon < 0 ? line : line.substring(0, colon);
                if (!"data".equals(field)) {
                    continue;
                }
                String value = colon < 0 ? "" : line.substring(colon + 1);
                if (value.startsWith(" ")) {
                    value = value.substring(1);
                }
                dataLines.add(value);
            }
            JsonNode matching = parseSseEvent(dataLines, expectedId, method);
            if (matching != null) {
                return matching;
            }
            throw protocolError(method, "SSE stream ended before the matching response arrived");
        }
    }

    private JsonNode parseSseEvent(List<String> dataLines, JsonNode expectedId, String method) {
        if (dataLines.isEmpty()) {
            return null;
        }
        String data = String.join("\n", dataLines);
        JsonNode event;
        try {
            event = JSON.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(data);
        } catch (JsonProcessingException exception) {
            throw protocolError(method, "SSE data is not valid JSON", exception);
        }
        JsonNode eventId = event == null ? null : event.get("id");
        return eventId != null && eventId.equals(expectedId) ? event : null;
    }

    private <T> T parseWithTimeout(InputStream body, Duration timeout, String method,
                                   IoOperation<T> operation) {
        CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
            try {
                return operation.run();
            } catch (McpException exception) {
                throw exception;
            } catch (IOException exception) {
                throw new McpException(McpErrorKind.PROTOCOL_ERROR,
                        "MCP " + serverName() + ": failed to read HTTP response for " + method + ".",
                        exception);
            }
        });
        try {
            return future.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            closeBody(body);
            future.cancel(true);
            throw timeout(method, exception);
        } catch (InterruptedException exception) {
            closeBody(body);
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new McpException(McpErrorKind.REQUEST_FAILED,
                    "MCP " + serverName() + ": interrupted while reading HTTP response for " + method + ".",
                    exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof McpException mcpException) {
                throw mcpException;
            }
            throw new McpException(McpErrorKind.PROTOCOL_ERROR,
                    "MCP " + serverName() + ": failed to parse HTTP response for " + method + ".",
                    cause);
        }
    }

    private static Duration remaining(long deadlineNanos) {
        return Duration.ofNanos(Math.max(1, deadlineNanos - System.nanoTime()));
    }

    private McpException timeout(String method, Exception cause) {
        return new McpException(McpErrorKind.TIMEOUT,
                "MCP " + serverName() + ": HTTP request timed out for " + method + ".", cause);
    }

    private McpException protocolError(String method, String detail) {
        return new McpException(McpErrorKind.PROTOCOL_ERROR,
                "MCP " + serverName() + ": invalid HTTP response for " + method + ": " + detail + ".");
    }

    private McpException protocolError(String method, String detail, Throwable cause) {
        return new McpException(McpErrorKind.PROTOCOL_ERROR,
                "MCP " + serverName() + ": invalid HTTP response for " + method + ": " + detail + ".",
                cause);
    }

    private static boolean isConnectionFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ConnectException
                    || current instanceof HttpConnectTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static void closeBody(HttpResponse<InputStream> response) {
        if (response != null) {
            closeBody(response.body());
        }
    }

    private static void closeBody(InputStream body) {
        if (body == null) {
            return;
        }
        try {
            body.close();
        } catch (IOException ignored) {
        }
    }

    @FunctionalInterface
    private interface IoOperation<T> {
        T run() throws IOException;
    }
}
