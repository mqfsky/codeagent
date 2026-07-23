package minicode.core.loop;

import minicode.core.step.AssistantStep;

import java.util.Optional;

/**
 * Validates a candidate final response before an agent turn is allowed to complete.
 *
 * <p>An empty result accepts the response. A non-empty result rejects it and supplies
 * a stable reason that can be sent back to the model for a focused retry.</p>
 */
@FunctionalInterface
public interface AssistantCompletionGuard {
    Optional<String> rejectionReason(AssistantStep step);

    static AssistantCompletionGuard acceptAll() {
        return ignored -> Optional.empty();
    }
}
