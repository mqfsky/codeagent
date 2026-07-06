package minicode.session.plan;

import java.util.List;
import java.util.Objects;

/**
 * 一轮 turn 结束后需要落盘的动作计划。
 *
 * @param actions 持久化动作列表
 */
public record TurnPersistencePlan(List<PersistenceAction> actions) {
    public TurnPersistencePlan {
        actions = List.copyOf(Objects.requireNonNull(actions, "actions"));
    }

    public static TurnPersistencePlan empty() {
        return new TurnPersistencePlan(List.of());
    }
}
