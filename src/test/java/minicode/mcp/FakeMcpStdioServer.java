package minicode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class FakeMcpStdioServer {
    private static final ObjectMapper JSON = new ObjectMapper();

    private FakeMcpStdioServer() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "happy" : args[0];
        if ("hang".equals(mode)) {
            Thread.sleep(60_000);
            return;
        }
        if ("invalid-json".equals(mode) || "trailing-json".equals(mode)) {
            System.out.println("trailing-json".equals(mode) ? "{} trailing-junk" : "not-json");
            System.out.flush();
            Thread.sleep(60_000);
            return;
        }

        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            JsonNode request = readMessage(stdin);
            if (request == null) {
                return;
            }
            if (!request.has("id")) {
                continue;
            }
            String method = request.path("method").asText();
            JsonNode id = request.get("id");
            if ("initialize".equals(method)) {
                handleInitialize(mode, request, id);
            } else if ("tools/list".equals(method)) {
                writeTools(id);
            } else if ("tools/call".equals(method)) {
                writeToolCall(id, request);
            }
        }
    }

    private static void handleInitialize(String mode, JsonNode request, JsonNode id) throws IOException {
        if (!AbstractMcpClient.PROTOCOL_VERSION_2024_11_05.equals(
                request.path("params").path("protocolVersion").asText())) {
            writeError(id, -32602, "unexpected requested protocol version");
            return;
        }
        if ("initialize-error".equals(mode)) {
            writeError(id, -32000, "initialize rejected");
            return;
        }

        ObjectNode result = JSON.createObjectNode();
        result.put("protocolVersion", switch (mode) {
            case "new-version" -> AbstractMcpClient.PROTOCOL_VERSION_2025_11_25;
            case "unsupported-version" -> "2099-01-01";
            default -> AbstractMcpClient.PROTOCOL_VERSION_2024_11_05;
        });
        result.putObject("capabilities").putObject("tools");
        result.putObject("serverInfo").put("name", "fake").put("version", "1.0.0");
        result.put("instructions", "Use Echo Tool only when echoing user-provided text.");
        JsonNode responseId = "id-mismatch".equals(mode)
                ? JSON.getNodeFactory().numberNode(id.asInt() + 1)
                : id;
        writeResult(responseId, result);
    }

    private static void writeTools(JsonNode id) throws IOException {
        ObjectNode result = JSON.createObjectNode();
        ObjectNode echo = result.putArray("tools").addObject();
        echo.put("name", "Echo Tool");
        echo.put("description", "Echoes text.");
        echo.putObject("inputSchema").put("type", "object").putObject("properties")
                .putObject("value").put("type", "string");
        ObjectNode failing = result.withArray("tools").addObject();
        failing.put("name", "Fail Tool");
        failing.put("description", "Returns isError.");
        writeResult(id, result);
    }

    private static void writeToolCall(JsonNode id, JsonNode request) throws IOException {
        String name = request.path("params").path("name").asText();
        ObjectNode result = JSON.createObjectNode();
        if ("Fail Tool".equals(name)) {
            result.put("isError", true);
            result.putArray("content").addObject().put("type", "text").put("text", "remote failed");
        } else {
            result.put("isError", false);
            String value = request.path("params").path("arguments").path("value").asText();
            result.putArray("content").addObject().put("type", "text").put("text", "echo: " + value);
            result.putObject("structuredContent").put("seen", value);
        }
        writeResult(id, result);
    }

    private static JsonNode readMessage(BufferedReader stdin) throws IOException {
        String line = stdin.readLine();
        return line == null ? null : JSON.readTree(line);
    }

    private static void writeResult(JsonNode id, JsonNode result) throws IOException {
        ObjectNode response = JSON.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        writeMessage(response);
    }

    private static void writeError(JsonNode id, int code, String message) throws IOException {
        ObjectNode response = JSON.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.putObject("error").put("code", code).put("message", message);
        writeMessage(response);
    }

    private static void writeMessage(JsonNode message) throws IOException {
        System.out.println(JSON.writeValueAsString(message));
        System.out.flush();
    }
}
