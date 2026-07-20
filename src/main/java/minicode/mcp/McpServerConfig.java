package minicode.mcp;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class McpServerConfig {
    private static final Duration DEFAULT_INITIALIZE_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_CALL_TIMEOUT = Duration.ofSeconds(30);

    private final String command;
    private final List<String> args;
    private final String cwd;
    private final Map<String, String> env;
    private final String url;
    private final Map<String, String> headers;
    private final boolean enabled;
    private final Duration initializeTimeout;
    private final Duration callTimeout;

    public McpServerConfig(String command, List<String> args, String cwd, Map<String, String> env,
                           boolean enabled, Duration initializeTimeout, Duration callTimeout) {
        this(Objects.requireNonNull(command, "command"), args, cwd, env, null, Map.of(), enabled,
                initializeTimeout, callTimeout);
    }

    public McpServerConfig(String command, List<String> args, String cwd, Map<String, String> env,
                           String url, Map<String, String> headers, boolean enabled,
                           Duration initializeTimeout, Duration callTimeout) {
        this.command = command == null ? "" : command.trim();
        this.args = List.copyOf(args == null ? List.of() : args);
        this.cwd = cwd;
        this.env = Map.copyOf(env == null ? Map.of() : env);
        this.url = url == null ? null : url.trim();
        this.headers = Map.copyOf(headers == null ? Map.of() : headers);
        this.enabled = enabled;
        this.initializeTimeout = initializeTimeout == null ? DEFAULT_INITIALIZE_TIMEOUT : initializeTimeout;
        this.callTimeout = callTimeout == null ? DEFAULT_CALL_TIMEOUT : callTimeout;
        if (this.initializeTimeout.isNegative() || this.initializeTimeout.isZero()) {
            throw new IllegalArgumentException("initializeTimeout must be positive");
        }
        if (this.callTimeout.isNegative() || this.callTimeout.isZero()) {
            throw new IllegalArgumentException("callTimeout must be positive");
        }
    }

    public String command() {
        return command;
    }

    public List<String> args() {
        return args;
    }

    public Optional<String> cwd() {
        return Optional.ofNullable(cwd).filter(value -> !value.isBlank());
    }

    public Map<String, String> env() {
        return env;
    }

    public Optional<String> url() {
        return Optional.ofNullable(url).filter(value -> !value.isBlank());
    }

    public Map<String, String> headers() {
        return headers;
    }

    public boolean enabled() {
        return enabled;
    }

    public Duration initializeTimeout() {
        return initializeTimeout;
    }

    public Duration callTimeout() {
        return callTimeout;
    }

    public Duration requestTimeout() {
        return callTimeout;
    }

    public TransportKind transportKind() {
        boolean hasCommand = !command.isBlank();
        boolean hasUrl = url().isPresent();
        if (hasCommand == hasUrl) {
            return TransportKind.INVALID;
        }
        if (hasCommand) {
            return TransportKind.STDIO;
        }
        return isValidHttpEndpoint(url) ? TransportKind.STREAMABLE_HTTP : TransportKind.INVALID;
    }

    public String endpointSummary() {
        if (transportKind() == TransportKind.STREAMABLE_HTTP) {
            return sanitizedHttpEndpoint(url);
        }
        if (transportKind() == TransportKind.INVALID) {
            return "invalid MCP endpoint configuration";
        }
        return (command + " " + String.join(" ", args)).trim();
    }

    public String commandSummary() {
        return endpointSummary();
    }

    private static boolean isValidHttpEndpoint(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            return uri.isAbsolute()
                    && scheme != null
                    && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    && uri.getHost() != null;
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private static String sanitizedHttpEndpoint(String value) {
        try {
            URI uri = new URI(value);
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(),
                    uri.getPath(), null, null).toString();
        } catch (URISyntaxException exception) {
            return "invalid MCP endpoint configuration";
        }
    }

    public enum TransportKind {
        STDIO,
        STREAMABLE_HTTP,
        INVALID
    }
}
