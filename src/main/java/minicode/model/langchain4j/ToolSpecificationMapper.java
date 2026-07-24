package minicode.model.langchain4j;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNullSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import minicode.tools.api.Tool;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 把 CodeAgent 的 JSON 工具 Schema 转换成 LangChain4j 工具声明。
 *
 * <p>LangChain4j 的 Schema 类型只能表达 JSON Schema 的一个子集，因此这里先构造一份
 * 可以被 LangChain4j 正常序列化的表示；发送请求前，HTTP 兼容层还会把原始 Schema
 * 完整写回请求。遇到无法用强类型表示的嵌套节点时，尽量在最小范围内使用
 * {@link JsonRawSchema}，避免扩大信息损失。</p>
 */
public final class ToolSpecificationMapper {
    // 每种强类型 Schema 只接受 LangChain4j 能无损表达的关键字；
    // 一旦出现 default、pattern、oneOf 等额外约束，就改用 JsonRawSchema。
    private static final Set<String> STRING_KEYS = Set.of("type", "description");
    private static final Set<String> INTEGER_KEYS = Set.of("type", "description");
    private static final Set<String> NUMBER_KEYS = Set.of("type", "description");
    private static final Set<String> BOOLEAN_KEYS = Set.of("type", "description");
    private static final Set<String> NULL_KEYS = Set.of("type");
    private static final Set<String> ENUM_KEYS = Set.of("type", "description", "enum");
    private static final Set<String> OBJECT_KEYS =
            Set.of("type", "description", "properties", "required", "additionalProperties", "$defs");
    private static final Set<String> ARRAY_KEYS = Set.of("type", "description", "items");

    /**
     * 为当前请求中的全部工具生成请求级 {@link ToolSpecification}。
     *
     * @param tools 当前 Agent 可见的工具列表
     * @return 与工具顺序一致的 LangChain4j 工具声明
     */
    public List<ToolSpecification> map(List<Tool> tools) {
        List<Tool> source = List.copyOf(Objects.requireNonNull(tools, "tools"));
        List<ToolSpecification> specifications = new ArrayList<>(source.size());
        for (Tool tool : source) {
            Tool actual = Objects.requireNonNull(tool, "tool");
            specifications.add(ToolSpecification.builder()
                    .name(actual.metadata().name())
                    .description(actual.metadata().description())
                    .parameters(mapRoot(actual.metadata().name(), actual.inputSchema()))
                    .build());
        }
        return List.copyOf(specifications);
    }

    private JsonObjectSchema mapRoot(String toolName, JsonNode schema) {
        JsonNode actual = Objects.requireNonNull(schema, "inputSchema");

        // CodeAgent 的工具调用输入必须是 JSON object。根节点不是 object 时立即失败，
        // 避免模型返回参数后才在 ToolRegistry 前出现含糊的类型错误。
        if (!actual.isObject()) {
            throw new IllegalArgumentException("Tool " + toolName
                    + " input schema root must be a JSON object");
        }

        JsonNode type = actual.get("type");
        // 允许省略 type，但只要显式声明，就必须严格为 "object"。
        if (type != null && (!type.isTextual() || !"object".equals(type.asText()))) {
            throw new IllegalArgumentException("Tool " + toolName
                    + " input schema root type must be object");
        }
        return mapObject(actual, true);
    }

    private JsonObjectSchema mapObject(JsonNode schema, boolean root) {
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
        String description = optionalText(schema, "description", root);
        if (description != null) {
            builder.description(description);
        }

        JsonNode properties = schema.get("properties");
        if (properties != null && !properties.isNull()) {
            if (!properties.isObject()) {
                throw new IllegalArgumentException("\"properties\" must be a JSON object");
            }
            Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                // 每个子属性独立选择强类型或 Raw Schema，尽量保留可读的结构。
                builder.addProperty(field.getKey(), mapNested(field.getValue()));
            }
        }

        builder.required(stringArray(schema, "required"));

        JsonNode additionalProperties = schema.get("additionalProperties");
        if (additionalProperties != null && additionalProperties.isBoolean()) {
            builder.additionalProperties(additionalProperties.booleanValue());
        }

        JsonNode definitions = schema.get("$defs");
        if (definitions != null && !definitions.isNull()) {
            if (!definitions.isObject()) {
                throw new IllegalArgumentException("\"$defs\" must be a JSON object");
            }
            Map<String, JsonSchemaElement> mappedDefinitions = new LinkedHashMap<>();
            definitions.fields().forEachRemaining(entry -> {
                // $defs 中的定义与普通属性采用相同的递归映射规则。
                mappedDefinitions.put(entry.getKey(), mapNested(entry.getValue()));
            });
            builder.definitions(mappedDefinitions);
        }
        return builder.build();
    }

    private JsonSchemaElement mapNested(JsonNode schema) {
        if (schema == null || !schema.isObject()) {
            // JSON Schema 允许 true/false 这样的布尔 Schema，但 LangChain4j 此处要求对象形态。
            // 先放入可序列化的空占位，HTTP 兼容层随后会恢复原始布尔节点。
            return JsonRawSchema.from("{}");
        }
        if (hasNullValue(schema)) {
            // 显式 null 值可能具有特殊语义，强类型 Builder 无法保证无损，直接保留原 JSON。
            return raw(schema);
        }

        JsonNode enumNode = schema.get("enum");
        if (enumNode != null) {
            // 只有纯字符串枚举且不含其他约束时，才使用 LangChain4j 的 JsonEnumSchema。
            if (keysFit(schema, ENUM_KEYS)
                    && hasValidDescription(schema)
                    && enumNode.isArray()
                    && allTextual(enumNode)
                    && (schema.get("type") == null || "string".equals(schema.path("type").asText()))) {
                return JsonEnumSchema.builder()
                        .description(optionalText(schema, "description", false))
                        .enumValues(toStrings(enumNode))
                        .build();
            }
            return raw(schema);
        }

        JsonNode typeNode = schema.get("type");
        if (typeNode == null || !typeNode.isTextual()) {
            // union type、$ref、oneOf 等复杂结构统一交给 JsonRawSchema。
            return raw(schema);
        }

        // 简单类型走强类型 Builder，便于 LangChain4j 正常检查和序列化；
        // 出现不支持的关键字时则回退 Raw，避免悄悄删除约束。
        String type = typeNode.asText();
        return switch (type) {
            case "string" -> keysFit(schema, STRING_KEYS) && hasValidDescription(schema)
                    ? JsonStringSchema.builder()
                            .description(optionalText(schema, "description", false))
                            .build()
                    : raw(schema);
            case "integer" -> keysFit(schema, INTEGER_KEYS) && hasValidDescription(schema)
                    ? JsonIntegerSchema.builder()
                            .description(optionalText(schema, "description", false))
                            .build()
                    : raw(schema);
            case "number" -> keysFit(schema, NUMBER_KEYS) && hasValidDescription(schema)
                    ? JsonNumberSchema.builder()
                            .description(optionalText(schema, "description", false))
                            .build()
                    : raw(schema);
            case "boolean" -> keysFit(schema, BOOLEAN_KEYS) && hasValidDescription(schema)
                    ? JsonBooleanSchema.builder()
                            .description(optionalText(schema, "description", false))
                            .build()
                    : raw(schema);
            case "null" -> keysFit(schema, NULL_KEYS) ? new JsonNullSchema() : raw(schema);
            case "array" -> mapArrayOrRaw(schema);
            case "object" -> mapObjectOrRaw(schema);
            default -> raw(schema);
        };
    }

    private JsonSchemaElement mapArrayOrRaw(JsonNode schema) {
        if (!keysFit(schema, ARRAY_KEYS) || !hasValidDescription(schema)) {
            return raw(schema);
        }
        JsonArraySchema.Builder builder = JsonArraySchema.builder()
                .description(optionalText(schema, "description", false));
        JsonNode items = schema.get("items");
        if (items != null) {
            if (!items.isObject()) {
                // tuple/boolean items 不能由 JsonArraySchema 精确表达，保留原始节点。
                return raw(schema);
            }
            builder.items(mapNested(items));
        }
        return builder.build();
    }

    private JsonSchemaElement mapObjectOrRaw(JsonNode schema) {
        if (!keysFit(schema, OBJECT_KEYS) || !hasValidDescription(schema)) {
            return raw(schema);
        }
        JsonNode additionalProperties = schema.get("additionalProperties");
        if (additionalProperties != null
                && !additionalProperties.isNull()
                && !additionalProperties.isBoolean()) {
            // additionalProperties 也可以是一份 Schema；LangChain4j Builder 只接受 boolean。
            return raw(schema);
        }
        return mapObject(schema, false);
    }

    private static String optionalText(JsonNode schema, String field, boolean root) {
        JsonNode value = schema.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            if (root) {
                throw new IllegalArgumentException("\"" + field + "\" must be a string");
            }
            return null;
        }
        return value.asText();
    }

    private static List<String> stringArray(JsonNode schema, String field) {
        JsonNode value = schema.get(field);
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (!value.isArray() || !allTextual(value)) {
            throw new IllegalArgumentException("\"" + field + "\" must be an array of strings");
        }
        return toStrings(value);
    }

    private static List<String> toStrings(JsonNode values) {
        List<String> result = new ArrayList<>(values.size());
        values.forEach(value -> result.add(value.asText()));
        return List.copyOf(result);
    }

    private static boolean allTextual(JsonNode values) {
        for (JsonNode value : values) {
            if (!value.isTextual()) {
                return false;
            }
        }
        return true;
    }

    private static boolean keysFit(JsonNode schema, Set<String> allowed) {
        Iterator<String> fields = schema.fieldNames();
        while (fields.hasNext()) {
            if (!allowed.contains(fields.next())) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasNullValue(JsonNode schema) {
        Iterator<JsonNode> values = schema.elements();
        while (values.hasNext()) {
            if (values.next().isNull()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasValidDescription(JsonNode schema) {
        JsonNode description = schema.get("description");
        return description == null || description.isTextual();
    }

    private static JsonRawSchema raw(JsonNode schema) {
        return JsonRawSchema.from(schema.toString());
    }
}
