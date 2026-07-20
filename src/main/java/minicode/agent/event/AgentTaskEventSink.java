package minicode.agent.event;

import java.util.Objects;

@FunctionalInterface
public interface AgentTaskEventSink {
    void onEvent(AgentTaskEvent event);

    static AgentTaskEventSink noOp() {
        return event -> Objects.requireNonNull(event, "event");
    }
}
