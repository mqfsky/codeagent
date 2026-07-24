package minicode.integrations.feishu.calendar;

import minicode.core.turn.CancellationToken;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * Executes one exact lark-cli argv vector with optional standard input.
 */
@FunctionalInterface
public interface LarkCliProcessExecutor {
    LarkCliProcessResult execute(List<String> argv,
                                 String stdin,
                                 Duration timeout,
                                 CancellationToken cancellationToken)
            throws IOException, InterruptedException, TimeoutException;
}
