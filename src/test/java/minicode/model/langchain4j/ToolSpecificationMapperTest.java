package minicode.model.langchain4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import minicode.tools.api.Tool;
import minicode.tools.api.ToolContext;
import minicode.tools.api.ValidationResult;
import minicode.tools.metadata.ToolCapability;
import minicode.tools.metadata.ToolMetadata;
import minicode.tools.metadata.ToolOrigin;
import minicode.tools.metadata.ToolStatus;
import minicode.tools.result.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolSpecificationMapperTest {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void mapsObjectSchemaAndUsesRawSchemaForUnsupportedNestedKeywords() throws Exception {
        ObjectNode schema = JSON.objectNode();
        schema.put("description", "Search input");
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("query")
                .put("type", "string")
                .put("description", "Search query");
        properties.putObject("pattern")
                .put("type", "string")
                .put("pattern", "^[a-z]+$");
        properties.putObject("oddDescription")
                .put("type", "string")
                .put("description", 7);
        properties.putObject("tags")
                .put("type", "array")
                .set("items", JSON.objectNode().put("type", "string"));
        ObjectNode options = properties.putObject("options");
        options.put("type", "object");
        options.putObject("properties").putObject("limit").put("type", "integer");
        options.putArray("required").add("limit");
        schema.putArray("required").add("query");

        ToolSpecification specification = new ToolSpecificationMapper()
                .map(List.of(tool("search", schema)))
                .getFirst();

        assertEquals("search", specification.name());
        assertEquals("test tool", specification.description());
        JsonObjectSchema parameters = specification.parameters();
        assertEquals("Search input", parameters.description());
        assertEquals(List.of("query"), parameters.required());
        assertEquals(Boolean.FALSE, parameters.additionalProperties());
        assertInstanceOf(JsonStringSchema.class, parameters.properties().get("query"));
        assertInstanceOf(JsonRawSchema.class, parameters.properties().get("pattern"));
        assertInstanceOf(JsonRawSchema.class, parameters.properties().get("oddDescription"));
        assertInstanceOf(JsonArraySchema.class, parameters.properties().get("tags"));
        assertInstanceOf(JsonObjectSchema.class, parameters.properties().get("options"));

        JsonNode serialized = MAPPER.readTree(specification.toJson());
        assertEquals("object", serialized.at("/parameters/type").asText());
        assertEquals("^[a-z]+$", serialized.at("/parameters/properties/pattern/pattern").asText());
        assertEquals("string", serialized.at("/parameters/properties/tags/items/type").asText());
    }

    @Test
    void preservesToolOrderAndAcceptsMissingRootType() {
        ObjectNode firstSchema = JSON.objectNode();
        firstSchema.putObject("properties").putObject("value").put("type", "boolean");
        ObjectNode secondSchema = JSON.objectNode().put("type", "object");

        List<ToolSpecification> specifications = new ToolSpecificationMapper().map(List.of(
                tool("first", firstSchema),
                tool("second", secondSchema)
        ));

        assertEquals(List.of("first", "second"),
                specifications.stream().map(ToolSpecification::name).toList());
        assertEquals(List.of(), specifications.getFirst().parameters().required());
        assertTrue(specifications.get(1).parameters().properties().isEmpty());
    }

    @Test
    void acceptsBooleanNestedSchemasForLosslessWireRestoration() {
        ObjectNode schema = JSON.objectNode().put("type", "object");
        schema.putObject("properties").put("anything", true);
        schema.putObject("$defs").put("never", false);

        ToolSpecification specification =
                new ToolSpecificationMapper().map(List.of(tool("boolean-schema", schema))).getFirst();

        assertInstanceOf(JsonRawSchema.class, specification.parameters().properties().get("anything"));
        assertInstanceOf(JsonRawSchema.class, specification.parameters().definitions().get("never"));
    }

    @Test
    void rejectsNonObjectRootSchemas() {
        ArrayNode arrayRoot = JSON.arrayNode().add("not-an-object");
        ObjectNode explicitArray = JSON.objectNode().put("type", "array");
        ObjectNode unionType = JSON.objectNode();
        unionType.putArray("type").add("object").add("null");

        ToolSpecificationMapper mapper = new ToolSpecificationMapper();

        assertThrows(IllegalArgumentException.class,
                () -> mapper.map(List.of(tool("array-root", arrayRoot))));
        assertThrows(IllegalArgumentException.class,
                () -> mapper.map(List.of(tool("array-type", explicitArray))));
        assertThrows(IllegalArgumentException.class,
                () -> mapper.map(List.of(tool("union-type", unionType))));
    }

    private static Tool tool(String name, JsonNode schema) {
        ToolMetadata metadata = new ToolMetadata(
                name,
                "test tool",
                schema,
                ToolOrigin.BUILTIN,
                Set.of(ToolCapability.READ),
                ToolStatus.AVAILABLE
        );
        return new Tool() {
            @Override
            public ToolMetadata metadata() {
                return metadata;
            }

            @Override
            public JsonNode inputSchema() {
                return schema;
            }

            @Override
            public ValidationResult validateInput(JsonNode input) {
                return ValidationResult.valid(input);
            }

            @Override
            public ToolResult run(JsonNode normalizedInput, ToolContext toolContext) {
                return ToolResult.ok("ok");
            }
        };
    }
}
