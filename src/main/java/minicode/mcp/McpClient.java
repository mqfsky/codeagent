package minicode.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public interface McpClient extends AutoCloseable {
    McpInitialization start();

    List<McpToolDescriptor> listTools();

    JsonNode callTool(String name, JsonNode arguments);

    @Override
    void close();
}
