package minicode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

final class FakeMcpHttpServer implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Queue<Responder> responders = new ArrayDeque<>();
    private final List<RecordedRequest> requests = new ArrayList<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    FakeMcpHttpServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create fake MCP HTTP server", exception);
        }
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    synchronized void enqueue(Response response) {
        responders.add(request -> response);
    }

    synchronized void enqueue(Responder responder) {
        responders.add(responder);
    }

    URI endpoint(String rawPath) {
        String path = rawPath.startsWith("/") ? rawPath : "/" + rawPath;
        return URI.create("http://" + server.getAddress().getHostString() + ":"
                + server.getAddress().getPort() + path);
    }

    synchronized List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    void assertHealthy() {
        Throwable throwable = failure.get();
        if (throwable != null) {
            throw new AssertionError("Fake MCP HTTP server failed", throwable);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) {
        try (exchange) {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            RecordedRequest request = new RecordedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI(),
                    copyHeaders(exchange),
                    new String(requestBody, StandardCharsets.UTF_8)
            );
            Responder responder;
            synchronized (this) {
                requests.add(request);
                responder = responders.poll();
            }
            Response response = responder == null
                    ? Response.text(500, "text/plain", "No scripted response")
                    : responder.respond(request);
            sleep(response.beforeHeadersDelay());
            response.headers().forEach(exchange.getResponseHeaders()::set);
            if (response.contentType() != null) {
                exchange.getResponseHeaders().set("Content-Type", response.contentType());
            }
            byte[] responseBody = response.body() == null
                    ? new byte[0]
                    : response.body().getBytes(StandardCharsets.UTF_8);
            long responseLength = response.body() == null
                    ? -1
                    : response.chunked() ? 0 : responseBody.length;
            exchange.sendResponseHeaders(response.status(), responseLength);
            if (response.body() != null) {
                exchange.getResponseBody().write(responseBody);
                exchange.getResponseBody().flush();
                sleep(response.afterFlushDelay());
            }
        } catch (Throwable throwable) {
            if (!(throwable instanceof InterruptedException)) {
                failure.compareAndSet(null, throwable);
            }
            if (throwable instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static Map<String, List<String>> copyHeaders(HttpExchange exchange) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, List.copyOf(values)));
        return Map.copyOf(headers);
    }

    private static void sleep(Duration duration) throws InterruptedException {
        if (!duration.isZero()) {
            Thread.sleep(duration);
        }
    }

    @FunctionalInterface
    interface Responder {
        Response respond(RecordedRequest request) throws Exception;
    }

    record RecordedRequest(String method, URI uri, Map<String, List<String>> headers, String body) {
        String header(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .findFirst()
                    .flatMap(entry -> entry.getValue().stream().findFirst())
                    .orElse(null);
        }

        JsonNode json() {
            try {
                return JSON.readTree(body);
            } catch (IOException exception) {
                throw new IllegalStateException("Request body is not JSON", exception);
            }
        }

        String rpcMethod() {
            JsonNode json = json();
            return json == null ? "" : json.path("method").asText("");
        }
    }

    record Response(int status, String contentType, Map<String, String> headers, String body,
                    boolean chunked, Duration beforeHeadersDelay, Duration afterFlushDelay) {
        Response {
            headers = Map.copyOf(headers == null ? Map.of() : headers);
            beforeHeadersDelay = beforeHeadersDelay == null ? Duration.ZERO : beforeHeadersDelay;
            afterFlushDelay = afterFlushDelay == null ? Duration.ZERO : afterFlushDelay;
        }

        static Response empty(int status) {
            return new Response(status, null, Map.of(), null, false, Duration.ZERO, Duration.ZERO);
        }

        static Response text(int status, String contentType, String body) {
            return new Response(status, contentType, Map.of(), body, false,
                    Duration.ZERO, Duration.ZERO);
        }

        static Response json(String body) {
            return text(200, "application/json; charset=utf-8", body);
        }

        static Response jsonResult(RecordedRequest request, JsonNode result) {
            JsonNode id = request.json().get("id");
            var envelope = JSON.createObjectNode();
            envelope.put("jsonrpc", "2.0");
            envelope.set("id", id);
            envelope.set("result", result);
            return json(envelope.toString());
        }

        Response withHeader(String name, String value) {
            Map<String, String> updated = new LinkedHashMap<>(headers);
            updated.put(name, value);
            return new Response(status, contentType, updated, body, chunked,
                    beforeHeadersDelay, afterFlushDelay);
        }

        Response asChunked() {
            return new Response(status, contentType, headers, body, true,
                    beforeHeadersDelay, afterFlushDelay);
        }

        Response beforeHeaders(Duration delay) {
            return new Response(status, contentType, headers, body, chunked, delay, afterFlushDelay);
        }

        Response afterFlush(Duration delay) {
            return new Response(status, contentType, headers, body, chunked,
                    beforeHeadersDelay, delay);
        }
    }
}
