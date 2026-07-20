package minicode.tools.builtin;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.agent.model.AgentRunMode;
import minicode.agent.model.AgentRunResult;
import minicode.agent.model.AgentTaskSnapshot;
import minicode.agent.model.AgentTaskStatus;
import minicode.agent.model.AgentType;

import java.util.List;

/** 内置 Agent 任务工具共用的稳定 JSON 渲染器。 */
final class AgentTaskToolJson {
    static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private AgentTaskToolJson() {
    }

    static String backgroundAccepted(AgentTaskSnapshot snapshot) {
        ObjectNode json = JSON.objectNode();
        json.put("task_id", snapshot.taskId());
        json.put("agent_id", snapshot.agentId());
        json.put("agent_type", externalName(snapshot.type()));
        json.put("run_mode", externalName(AgentRunMode.BACKGROUND));
        json.put("status", snapshot.status().name());
        json.put("description", snapshot.description());
        return json.toString();
    }

    static String synchronousResult(String taskId,
                                    String agentId,
                                    AgentType type,
                                    AgentRunResult result) {
        AgentTaskStatus status = result.cancelled()
                ? AgentTaskStatus.CANCELLED
                : result.error().isPresent() ? AgentTaskStatus.FAILED : AgentTaskStatus.COMPLETED;
        ObjectNode json = JSON.objectNode();
        json.put("task_id", taskId);
        json.put("agent_id", agentId);
        json.put("agent_type", externalName(type));
        json.put("run_mode", externalName(AgentRunMode.SYNC));
        json.put("status", status.name());
        json.put("stop_reason", result.stopReason());
        json.put("output", result.output());
        putOptionalText(json, "error", result.error().orElse(null));
        return json.toString();
    }

    static String taskList(String cwd, String sessionId, List<AgentTaskSnapshot> snapshots) {
        ObjectNode json = JSON.objectNode();
        json.put("cwd", cwd);
        json.put("session_id", sessionId);
        ArrayNode tasks = json.putArray("tasks");
        snapshots.forEach(snapshot -> tasks.add(snapshot(snapshot)));
        return json.toString();
    }

    static String taskStatus(AgentTaskSnapshot snapshot) {
        ObjectNode json = JSON.objectNode();
        json.set("task", snapshot(snapshot));
        return json.toString();
    }

    static String taskOutput(AgentTaskSnapshot snapshot, int offset, int limit) {
        String completeOutput = snapshot.output().orElse("");
        int start = Math.min(offset, completeOutput.length());
        int end = (int) Math.min(completeOutput.length(), (long) start + limit);

        ObjectNode json = JSON.objectNode();
        json.put("task_id", snapshot.taskId());
        json.put("status", snapshot.status().name());
        json.put("offset", start);
        json.put("limit", limit);
        json.put("next_offset", end);
        json.put("total_chars", completeOutput.length());
        json.put("truncated", end < completeOutput.length());
        json.put("output", completeOutput.substring(start, end));
        putOptionalText(json, "error", snapshot.error().orElse(null));
        return json.toString();
    }

    static String taskCancelled(AgentTaskSnapshot snapshot, boolean changed) {
        ObjectNode json = JSON.objectNode();
        json.put("task_id", snapshot.taskId());
        json.put("status", snapshot.status().name());
        json.put("cancelled", snapshot.status() == AgentTaskStatus.CANCELLED);
        json.put("changed", changed);
        return json.toString();
    }

    static String error(String code, String message) {
        ObjectNode json = JSON.objectNode();
        ObjectNode error = json.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return json.toString();
    }

    static String error(String code, RuntimeException exception, String fallback) {
        String message = exception.getMessage();
        return error(code, message == null || message.isBlank() ? fallback : message);
    }

    static ObjectNode snapshot(AgentTaskSnapshot snapshot) {
        ObjectNode json = JSON.objectNode();
        json.put("task_id", snapshot.taskId());
        json.put("agent_id", snapshot.agentId());
        json.put("agent_type", externalName(snapshot.type()));
        json.put("description", snapshot.description());
        json.put("parent_session_id", snapshot.parentSessionId());
        json.put("parent_turn_id", snapshot.parentTurnId());
        json.put("cwd", snapshot.cwd());
        json.put("status", snapshot.status().name());
        json.put("submitted_at", snapshot.submittedAt().toString());
        putOptionalText(json, "started_at", snapshot.startedAt().map(Object::toString).orElse(null));
        putOptionalText(json, "completed_at", snapshot.completedAt().map(Object::toString).orElse(null));
        json.put("has_output", snapshot.output().isPresent());
        json.put("output_chars", snapshot.output().map(String::length).orElse(0));
        putOptionalText(json, "error", snapshot.error().orElse(null));
        json.put("notification_delivered", snapshot.notificationDelivered());
        return json;
    }

    static String externalName(AgentType type) {
        return switch (type) {
            case EXPLORE -> "explore";
            case PLAN -> "plan";
            case GENERAL_PURPOSE -> "general-purpose";
        };
    }

    static String externalName(AgentRunMode mode) {
        return mode.name().toLowerCase(java.util.Locale.ROOT);
    }

    static AgentType parseAgentType(String value) {
        return switch (value) {
            case "explore" -> AgentType.EXPLORE;
            case "plan" -> AgentType.PLAN;
            case "general-purpose" -> AgentType.GENERAL_PURPOSE;
            default -> throw new IllegalArgumentException("Unsupported agent_type: " + value);
        };
    }

    private static void putOptionalText(ObjectNode json, String field, String value) {
        if (value == null) {
            json.putNull(field);
        } else {
            json.put(field, value);
        }
    }
}
