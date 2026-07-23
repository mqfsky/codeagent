package minicode.tui;

import minicode.permissions.model.PathIntent;
import minicode.permissions.model.PermissionChoice;
import minicode.permissions.model.PermissionContext;
import minicode.permissions.model.PermissionRequest;
import minicode.permissions.model.PermissionRequestDetails;
import minicode.permissions.model.PermissionRequestKind;
import minicode.permissions.model.PermissionResource;
import minicode.permissions.model.PermissionScope;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleInputCoordinatorTest {
    @Test
    void backgroundPermissionReceivesNextLineBeforeWaitingChatReader() throws Exception {
        try (PipedInputStream source = new PipedInputStream();
             PipedOutputStream input = new PipedOutputStream(source)) {
            ConsoleInputCoordinator coordinator = new ConsoleInputCoordinator(
                    new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8)));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ConsolePermissionPromptHandler handler = new ConsolePermissionPromptHandler(coordinator, output);

            CompletableFuture<String> chatLine = CompletableFuture.supplyAsync(() -> readLine(coordinator));
            CompletableFuture<minicode.permissions.model.PermissionPromptResult> permission =
                    CompletableFuture.supplyAsync(() -> handler.prompt(request()));
            waitUntil(() -> output.toString(StandardCharsets.UTF_8).contains("Permission choice:"));

            input.write("allow_once\n".getBytes(StandardCharsets.UTF_8));
            input.flush();

            assertTrue(permission.get(2, TimeUnit.SECONDS).allowed());
            assertFalse(chatLine.isDone(), "权限选择不应被普通聊天读取器消费");

            input.write("hello parent\n".getBytes(StandardCharsets.UTF_8));
            input.flush();
            assertEquals("hello parent", chatLine.get(2, TimeUnit.SECONDS));
        }
    }

    private static PermissionRequest request() {
        return new PermissionRequest(
                "background-command",
                PermissionRequestKind.PATH,
                new PermissionResource.PathResource(Path.of("."), PathIntent.WRITE),
                "Allow background command",
                new PermissionRequestDetails("Background command", "Review command", List.of("Command: pwd")),
                List.of(PermissionChoice.allowOnce("allow_once", "Allow once")),
                true,
                PermissionScope.ONCE,
                new PermissionContext("session", Optional.of("turn"), Optional.of("tool")));
    }

    private static String readLine(ConsoleInputCoordinator coordinator) {
        try {
            return coordinator.readLine();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(condition.getAsBoolean(), "condition was not met before timeout");
    }
}
