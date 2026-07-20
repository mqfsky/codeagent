package minicode.tui;

import minicode.agent.event.AgentTaskEvent;
import minicode.agent.event.AgentTaskEventSink;
import minicode.core.event.AgentEvent;
import minicode.core.event.AgentEventSink;
import minicode.permissions.api.PermissionPromptHandler;
import minicode.permissions.model.PermissionDecision;
import minicode.permissions.model.PermissionPromptResult;
import minicode.permissions.model.PermissionRequest;

import java.util.Objects;

public final class RendererTuiBridge implements AgentEventSink, AgentTaskEventSink, PermissionPromptHandler {
    private volatile RendererTuiShell shell;

    void attach(RendererTuiShell shell) {
        this.shell = Objects.requireNonNull(shell, "shell");
    }

    @Override
    public void onEvent(AgentEvent event) {
        RendererTuiShell current = shell;
        if (current != null) {
            current.onAgentEvent(event);
        }
    }

    @Override
    public void onEvent(AgentTaskEvent event) {
        RendererTuiShell current = shell;
        if (current != null) {
            current.onAgentTaskEvent(event);
        }
    }

    @Override
    public PermissionPromptResult prompt(PermissionRequest request) {
        RendererTuiShell current = shell;
        if (current == null) {
            return PermissionPromptResult.deny(
                    PermissionDecision.DENY_WITH_FEEDBACK,
                    "Renderer permission handler is not attached"
            );
        }
        // 这里
        return current.requestPermission(request);
    }
}
