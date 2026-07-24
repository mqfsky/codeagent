package minicode.tools.extension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.core.turn.CancellationPhase;
import minicode.core.turn.CancellationRequestedException;
import minicode.integrations.feishu.calendar.CalendarClockTime;
import minicode.integrations.feishu.calendar.CalendarDateSpec;
import minicode.integrations.feishu.calendar.CalendarDayPeriod;
import minicode.integrations.feishu.calendar.CalendarTimeResolutionException;
import minicode.integrations.feishu.calendar.CalendarTimeResolver;
import minicode.integrations.feishu.calendar.FeishuCalendarCreateRequest;
import minicode.integrations.feishu.calendar.FeishuCalendarCreateResult;
import minicode.integrations.feishu.calendar.FeishuCalendarGateway;
import minicode.integrations.feishu.calendar.FeishuCalendarGatewayException;
import minicode.integrations.feishu.calendar.ResolvedCalendarWindow;
import minicode.permissions.api.PermissionService;
import minicode.permissions.model.PermissionContext;
import minicode.permissions.model.PermissionDeniedException;
import minicode.permissions.model.PermissionResource;
import minicode.tools.api.Tool;
import minicode.tools.api.ToolContext;
import minicode.tools.api.ValidationResult;
import minicode.tools.metadata.ToolCapability;
import minicode.tools.metadata.ToolMetadata;
import minicode.tools.metadata.ToolOrigin;
import minicode.tools.metadata.ToolStatus;
import minicode.tools.result.ToolResult;
import minicode.tools.validation.ToolInputValidation;
import minicode.tools.validation.ValidatedInputBuilder;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 创建当前用户个人飞书主日历事件的强类型外部写工具。 */
public final class CreateFeishuCalendarEventTool implements Tool {
    public static final String NAME = "create_feishu_calendar_event";

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final Set<String> TOP_LEVEL_FIELDS = Set.of(
            "summary", "originalTimeText", "date", "startTime", "endTime",
            "durationMinutes", "reminderMinutes", "description"
    );
    private static final Set<String> DATE_FIELDS = Set.of("kind", "offsetDays", "year", "month", "day");
    private static final Set<String> TIME_FIELDS = Set.of("hour", "minute", "dayPeriod");
    private static final Set<String> DATE_KINDS = Set.of("RELATIVE_DAY", "MONTH_DAY");
    private static final Set<String> DAY_PERIODS = Set.of(
            "UNSPECIFIED", "MORNING", "AFTERNOON", "EVENING", "EARLY_MORNING"
    );
    private static final ObjectNode INPUT_SCHEMA = createInputSchema();
    private static final DateTimeFormatter PERMISSION_TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm XXX VV");
    private static final ToolMetadata METADATA = new ToolMetadata(
            NAME,
            "Create one private event in the authenticated user's primary Feishu calendar. "
                    + "Call only for the user's explicit request to create, add, or schedule an event, "
                    + "or for a direct clarification answer in that active request. "
                    + "Use structured date and clock fields; every successful write requires a user preview.",
            INPUT_SCHEMA,
            ToolOrigin.EXTENSION,
            Set.of(ToolCapability.EXTERNAL_WRITE),
            ToolStatus.AVAILABLE
    );

    private final PermissionService permissionService;
    private final CalendarTimeResolver timeResolver;
    private final FeishuCalendarGateway gateway;
    private final int defaultReminderMinutes;

    public CreateFeishuCalendarEventTool(PermissionService permissionService,
                                         CalendarTimeResolver timeResolver,
                                         FeishuCalendarGateway gateway,
                                         int defaultReminderMinutes) {
        this.permissionService = Objects.requireNonNull(permissionService, "permissionService");
        this.timeResolver = Objects.requireNonNull(timeResolver, "timeResolver");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        if (defaultReminderMinutes < 0 || defaultReminderMinutes > 20_160) {
            throw new IllegalArgumentException("defaultReminderMinutes must be between 0 and 20160");
        }
        this.defaultReminderMinutes = defaultReminderMinutes;
    }

    @Override
    public ToolMetadata metadata() {
        return METADATA;
    }

    @Override
    public JsonNode inputSchema() {
        return INPUT_SCHEMA;
    }

    @Override
    public ValidationResult validateInput(JsonNode input) {
        return ToolInputValidation.object(input)
                .custom((rawInput, builder) -> validateObject(rawInput, builder))
                .build();
    }

    @Override
    public ToolResult run(JsonNode input, ToolContext toolContext) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(toolContext, "toolContext");
        try {
            toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);

            String summary = input.path("summary").asText();
            String originalTimeText = input.path("originalTimeText").asText();
            CalendarDateSpec date = dateSpec(input.path("date"));
            CalendarClockTime startTime = clockTime(input.path("startTime"));
            Optional<CalendarClockTime> endTime = optionalClockTime(input.get("endTime"));
            Optional<Integer> durationMinutes = optionalInteger(input.get("durationMinutes"));
            int reminderMinutes = input.path("reminderMinutes").asInt(defaultReminderMinutes);
            Optional<String> description = optionalText(input.get("description"));

            ResolvedCalendarWindow window = timeResolver.resolve(
                    date,
                    startTime,
                    endTime,
                    durationMinutes
            );
            String actionFingerprint = actionFingerprint(
                    summary, window, reminderMinutes, description);
            PermissionResource.ExternalActionResource permissionResource =
                    new PermissionResource.ExternalActionResource(
                            "Feishu Calendar",
                            "Create event",
                            "Personal primary calendar",
                            actionFingerprint,
                            permissionFacts(
                                    originalTimeText,
                                    summary,
                                    window,
                                    reminderMinutes,
                                    description
                            )
                    );
            PermissionContext permissionContext = new PermissionContext(
                    toolContext.sessionId(),
                    toolContext.turnId(),
                    toolContext.toolUseId()
            );

            toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.PERMISSION_PROMPT);
            permissionService.ensureExternalAction(permissionResource, permissionContext);
            toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.PERMISSION_PROMPT);
            toolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);

            String idempotencyKey = idempotencyKey(toolContext, actionFingerprint);
            FeishuCalendarCreateResult result = gateway.create(
                    new FeishuCalendarCreateRequest(
                            summary,
                            window.start(),
                            window.end(),
                            reminderMinutes,
                            description
                    ),
                    idempotencyKey,
                    toolContext.cancellationToken()
            );
            // The external write has completed and returned a concrete event. A cancellation that
            // races with this point must not discard the success and encourage a duplicate retry.
            return ToolResult.ok(successJson(result, summary, window, reminderMinutes).toString());
        } catch (CancellationRequestedException exception) {
            throw exception;
        } catch (PermissionDeniedException exception) {
            return ToolResult.error(exception.getMessage());
        } catch (CalendarTimeResolutionException | IllegalArgumentException exception) {
            return ToolResult.error("Calendar time could not be resolved: " + safeMessage(exception));
        } catch (FeishuCalendarGatewayException exception) {
            return ToolResult.error("Feishu calendar creation failed: " + safeMessage(exception));
        }
    }

    private void validateObject(JsonNode input, ValidatedInputBuilder builder) {
        if (input == null || !input.isObject()) {
            return;
        }
        rejectUnknownFields(input, TOP_LEVEL_FIELDS, "", builder);
        copyRequiredText(input, "summary", 200, builder);
        copyRequiredText(input, "originalTimeText", 200, builder);

        ObjectNode date = validateDate(input.get("date"), builder);
        if (date != null) {
            builder.normalized().set("date", date);
        }
        ObjectNode startTime = validateClockTime(input.get("startTime"), "startTime", true, builder);
        if (startTime != null) {
            builder.normalized().set("startTime", startTime);
        }
        ObjectNode endTime = validateClockTime(input.get("endTime"), "endTime", false, builder);
        if (endTime != null) {
            builder.normalized().set("endTime", endTime);
        }

        copyOptionalInteger(input, "durationMinutes", 1, 1_440, builder);
        copyOptionalInteger(input, "reminderMinutes", 0, 20_160, builder);
        if (!builder.normalized().has("reminderMinutes")) {
            builder.normalized().put("reminderMinutes", defaultReminderMinutes);
        }
        copyOptionalText(input, "description", 4_000, builder);

        if (input.hasNonNull("endTime") && input.hasNonNull("durationMinutes")) {
            builder.addError("endTime and durationMinutes are mutually exclusive");
        }
    }

    private static ObjectNode validateDate(JsonNode node, ValidatedInputBuilder builder) {
        if (node == null || !node.isObject()) {
            builder.addError("date must exist and be an object");
            return null;
        }
        rejectUnknownFields(node, DATE_FIELDS, "date.", builder);
        String kind = requiredEnum(node, "kind", DATE_KINDS, "date.", builder);
        if (kind == null) {
            return null;
        }
        ObjectNode normalized = JSON.objectNode().put("kind", kind);
        if ("RELATIVE_DAY".equals(kind)) {
            Integer offsetDays = integer(node, "offsetDays", 0, 2, true, "date.", builder);
            if (offsetDays != null) {
                normalized.put("offsetDays", offsetDays);
            }
            rejectPresent(node, List.of("year", "month", "day"), "date.", builder);
        } else {
            Integer month = integer(node, "month", 1, 12, true, "date.", builder);
            Integer day = integer(node, "day", 1, 31, true, "date.", builder);
            Integer year = integer(node, "year", 1, 9_999, false, "date.", builder);
            if (month != null) {
                normalized.put("month", month);
            }
            if (day != null) {
                normalized.put("day", day);
            }
            if (year != null) {
                normalized.put("year", year);
            }
            rejectPresent(node, List.of("offsetDays"), "date.", builder);
        }
        return normalized;
    }

    private static ObjectNode validateClockTime(JsonNode node,
                                                String field,
                                                boolean required,
                                                ValidatedInputBuilder builder) {
        if (node == null || node.isNull()) {
            if (required) {
                builder.addError(field + " must exist and be an object");
            }
            return null;
        }
        if (!node.isObject()) {
            builder.addError(field + " must be an object");
            return null;
        }
        rejectUnknownFields(node, TIME_FIELDS, field + ".", builder);
        String dayPeriod = requiredEnum(node, "dayPeriod", DAY_PERIODS, field + ".", builder);
        Integer hour = integer(node, "hour", 0, 23, true, field + ".", builder);
        Integer minute = integer(node, "minute", 0, 59, false, field + ".", builder);
        int normalizedMinute = minute == null ? 0 : minute;
        if (dayPeriod == null || hour == null) {
            return null;
        }
        try {
            new CalendarClockTime(hour, normalizedMinute, CalendarDayPeriod.valueOf(dayPeriod));
        } catch (IllegalArgumentException exception) {
            builder.addError(field + " has inconsistent hour and dayPeriod: " + safeMessage(exception));
            return null;
        }
        return JSON.objectNode()
                .put("hour", hour)
                .put("minute", normalizedMinute)
                .put("dayPeriod", dayPeriod);
    }

    private static CalendarDateSpec dateSpec(JsonNode node) {
        String kind = node.path("kind").asText();
        if ("RELATIVE_DAY".equals(kind)) {
            return new CalendarDateSpec.RelativeDay(node.path("offsetDays").asInt());
        }
        Optional<Integer> year = node.has("year")
                ? Optional.of(node.path("year").asInt())
                : Optional.empty();
        return new CalendarDateSpec.MonthDay(
                year,
                node.path("month").asInt(),
                node.path("day").asInt()
        );
    }

    private static CalendarClockTime clockTime(JsonNode node) {
        return new CalendarClockTime(
                node.path("hour").asInt(),
                node.path("minute").asInt(),
                CalendarDayPeriod.valueOf(node.path("dayPeriod").asText())
        );
    }

    private static Optional<CalendarClockTime> optionalClockTime(JsonNode node) {
        return node == null || node.isNull() ? Optional.empty() : Optional.of(clockTime(node));
    }

    private static Optional<Integer> optionalInteger(JsonNode node) {
        return node == null || node.isNull() ? Optional.empty() : Optional.of(node.asInt());
    }

    private static Optional<String> optionalText(JsonNode node) {
        return node == null || node.isNull() ? Optional.empty() : Optional.of(node.asText());
    }

    private static List<String> permissionFacts(String originalTimeText,
                                                String summary,
                                                ResolvedCalendarWindow window,
                                                int reminderMinutes,
                                                Optional<String> description) {
        List<String> facts = new ArrayList<>();
        facts.add("Original expression: " + permissionPreviewText(originalTimeText));
        facts.add("Title: " + permissionPreviewText(summary));
        facts.add("Start: " + PERMISSION_TIME.format(window.start()));
        facts.add("End: " + PERMISSION_TIME.format(window.end()));
        facts.add("Reminder: " + reminderMinutes + " minutes before");
        description.ifPresent(value -> facts.add(
                "Description (" + value.length() + " chars): " + permissionPreviewText(value)));
        return List.copyOf(facts);
    }

    private static String actionFingerprint(String summary,
                                            ResolvedCalendarWindow window,
                                            int reminderMinutes,
                                            Optional<String> description) {
        String canonical = String.join("\n",
                summary,
                window.start().toInstant().toString(),
                window.end().toInstant().toString(),
                window.start().getZone().getId(),
                Integer.toString(reminderMinutes),
                description.orElse("")
        );
        return "sha256:" + HexFormat.of().formatHex(sha256(canonical));
    }

    private static String idempotencyKey(ToolContext context, String actionFingerprint) {
        // Keep the key stable across turns in the same session. If cancellation or transport
        // failure leaves the remote outcome unknown, a later retry remains idempotent.
        String source = context.sessionId() + "\n" + actionFingerprint;
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * Makes model-controlled text safe to place inside a terminal permission preview without
     * changing the value ultimately sent to Feishu.
     */
    private static String permissionPreviewText(String value) {
        StringBuilder preview = new StringBuilder(value.length());
        boolean pendingSpace = false;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                pendingSpace = preview.length() > 0;
                continue;
            }
            if (pendingSpace) {
                preview.append(' ');
                pendingSpace = false;
            }
            if (Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.FORMAT) {
                preview.append("\\u{")
                        .append(Integer.toHexString(codePoint).toUpperCase(java.util.Locale.ROOT))
                        .append('}');
            } else {
                preview.appendCodePoint(codePoint);
            }
        }
        return preview.toString();
    }

    private static byte[] sha256(String value) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static ObjectNode successJson(FeishuCalendarCreateResult result,
                                          String summary,
                                          ResolvedCalendarWindow window,
                                          int reminderMinutes) {
        ObjectNode output = JSON.objectNode();
        output.put("status", "created");
        output.put("eventId", result.eventId());
        result.appLink().ifPresent(value -> output.put("appLink", value));
        output.put("summary", summary);
        output.put("start", window.start().toString());
        output.put("end", window.end().toString());
        output.put("reminderMinutes", reminderMinutes);
        return output;
    }

    private static void copyRequiredText(JsonNode input,
                                         String field,
                                         int maxLength,
                                         ValidatedInputBuilder builder) {
        JsonNode node = input.get(field);
        if (node == null || !node.isTextual()) {
            builder.addError(field + " must exist and be a string");
            return;
        }
        String value = node.asText().trim();
        if (value.isEmpty()) {
            builder.addError(field + " must not be blank");
        } else if (value.length() > maxLength) {
            builder.addError(field + " must contain at most " + maxLength + " characters");
        } else {
            builder.normalized().put(field, value);
        }
    }

    private static void copyOptionalText(JsonNode input,
                                         String field,
                                         int maxLength,
                                         ValidatedInputBuilder builder) {
        JsonNode node = input.get(field);
        if (node == null || node.isNull()) {
            return;
        }
        if (!node.isTextual()) {
            builder.addError(field + " must be a string");
            return;
        }
        String value = node.asText().trim();
        if (value.isEmpty()) {
            return;
        }
        if (value.length() > maxLength) {
            builder.addError(field + " must contain at most " + maxLength + " characters");
            return;
        }
        builder.normalized().put(field, value);
    }

    private static void copyOptionalInteger(JsonNode input,
                                            String field,
                                            int minimum,
                                            int maximum,
                                            ValidatedInputBuilder builder) {
        Integer value = integer(input, field, minimum, maximum, false, "", builder);
        if (value != null) {
            builder.normalized().put(field, value);
        }
    }

    private static String requiredEnum(JsonNode object,
                                       String field,
                                       Set<String> allowed,
                                       String prefix,
                                       ValidatedInputBuilder builder) {
        JsonNode node = object.get(field);
        if (node == null || !node.isTextual()) {
            builder.addError(prefix + field + " must exist and be a string");
            return null;
        }
        String value = node.asText();
        if (!allowed.contains(value)) {
            builder.addError(prefix + field + " must be one of " + allowed.stream().sorted().toList());
            return null;
        }
        return value;
    }

    private static Integer integer(JsonNode object,
                                   String field,
                                   int minimum,
                                   int maximum,
                                   boolean required,
                                   String prefix,
                                   ValidatedInputBuilder builder) {
        JsonNode node = object.get(field);
        if (node == null || node.isNull()) {
            if (required) {
                builder.addError(prefix + field + " must exist and be an integer");
            }
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            builder.addError(prefix + field + " must be an integer");
            return null;
        }
        int value = node.asInt();
        if (value < minimum || value > maximum) {
            builder.addError(prefix + field + " must be between " + minimum + " and " + maximum);
            return null;
        }
        return value;
    }

    private static void rejectUnknownFields(JsonNode object,
                                            Set<String> allowed,
                                            String prefix,
                                            ValidatedInputBuilder builder) {
        object.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                builder.addError(prefix + field + " is not supported");
            }
        });
    }

    private static void rejectPresent(JsonNode object,
                                      List<String> fields,
                                      String prefix,
                                      ValidatedInputBuilder builder) {
        fields.stream()
                .filter(object::hasNonNull)
                .forEach(field -> builder.addError(prefix + field + " is not allowed for this date kind"));
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static ObjectNode createInputSchema() {
        ObjectNode schema = JSON.objectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");

        stringProperty(properties, "summary",
                "Short event title without the date or time phrase.");
        stringProperty(properties, "originalTimeText",
                "The user's original date/time phrase, preserved for review.");
        properties.set("date", dateSchema());
        properties.set("startTime", timeSchema(
                "Structured local start time. hour preserves the user's spoken hour."));
        properties.set("endTime", timeSchema(
                "Optional same-day end time. Do not use it for an overnight range."));
        integerProperty(properties, "durationMinutes", 1, 1_440,
                "Optional duration. Mutually exclusive with endTime; backend default is used when omitted.");
        integerProperty(properties, "reminderMinutes", 0, 20_160,
                "Optional reminder lead time; backend default is used when omitted.");
        stringProperty(properties, "description", "Optional event description.");

        ArrayNode required = schema.putArray("required");
        required.add("summary");
        required.add("originalTimeText");
        required.add("date");
        required.add("startTime");
        return schema;
    }

    private static ObjectNode dateSchema() {
        ObjectNode schema = JSON.objectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("description",
                "RELATIVE_DAY uses offsetDays 0=today, 1=tomorrow, 2=day after tomorrow. "
                        + "MONTH_DAY uses month/day and an optional explicit year.");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode kind = properties.putObject("kind");
        kind.put("type", "string");
        ArrayNode values = kind.putArray("enum");
        values.add("RELATIVE_DAY");
        values.add("MONTH_DAY");
        integerProperty(properties, "offsetDays", 0, 2,
                "Only for RELATIVE_DAY.");
        integerProperty(properties, "year", 1, 9_999,
                "Optional explicit year for MONTH_DAY.");
        integerProperty(properties, "month", 1, 12,
                "Only for MONTH_DAY.");
        integerProperty(properties, "day", 1, 31,
                "Only for MONTH_DAY.");
        schema.putArray("required").add("kind");
        return schema;
    }

    private static ObjectNode timeSchema(String description) {
        ObjectNode schema = JSON.objectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("description", description);
        ObjectNode properties = schema.putObject("properties");
        integerProperty(properties, "hour", 0, 23,
                "The hour as expressed by the user; dayPeriod determines any 12-to-24-hour conversion.");
        integerProperty(properties, "minute", 0, 59,
                "Minute, defaulting to zero when omitted.");
        ObjectNode period = properties.putObject("dayPeriod");
        period.put("type", "string");
        ArrayNode values = period.putArray("enum");
        DAY_PERIODS.stream().sorted().forEach(values::add);
        ArrayNode required = schema.putArray("required");
        required.add("hour");
        required.add("dayPeriod");
        return schema;
    }

    private static void stringProperty(ObjectNode properties, String name, String description) {
        ObjectNode property = properties.putObject(name);
        property.put("type", "string");
        property.put("description", description);
    }

    private static void integerProperty(ObjectNode properties,
                                        String name,
                                        int minimum,
                                        int maximum,
                                        String description) {
        ObjectNode property = properties.putObject(name);
        property.put("type", "integer");
        property.put("minimum", minimum);
        property.put("maximum", maximum);
        property.put("description", description);
    }
}
