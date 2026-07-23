package minicode.tui;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 为普通行模式统一读取控制台输入，并按当前状态分发给聊天循环或权限提示。
 *
 * <p>底层 {@link BufferedReader} 始终只由一个虚拟线程读取，避免后台子 Agent 请求权限时
 * 与 {@link MiniTui} 同时调用 {@code readLine()}，导致权限选择被当成普通用户消息。</p>
 */
public final class ConsoleInputCoordinator implements LineInput {
    private final BufferedReader reader;
    private final BlockingQueue<ReadResult> userLines = new LinkedBlockingQueue<>();
    private final BlockingQueue<ReadResult> permissionLines = new LinkedBlockingQueue<>();
    private final Object modeLock = new Object();
    private boolean permissionActive;
    private int userWaiters;
    private int permissionWaiters;

    public ConsoleInputCoordinator(BufferedReader reader) {
        this.reader = Objects.requireNonNull(reader, "reader");
        Thread.ofVirtual()
                .name("minicode-console-input")
                .start(this::readLoop);
    }

    /** 读取下一条普通聊天输入。 */
    @Override
    public String readLine() throws IOException {
        synchronized (modeLock) {
            userWaiters++;
            modeLock.notifyAll();
        }
        return take(userLines);
    }

    /** 读取下一条权限选择或拒绝反馈。 */
    String readPermissionLine() throws IOException {
        synchronized (modeLock) {
            permissionWaiters++;
            modeLock.notifyAll();
        }
        return take(permissionLines);
    }

    /** 在打印权限提示前切换输入路由；应用层会保证权限请求串行。 */
    void beginPermission() {
        synchronized (modeLock) {
            if (permissionActive) {
                throw new IllegalStateException("A permission prompt is already active");
            }
            permissionActive = true;
            modeLock.notifyAll();
        }
    }

    /** 权限交互结束后恢复普通聊天输入路由。 */
    void endPermission() {
        synchronized (modeLock) {
            permissionActive = false;
            modeLock.notifyAll();
        }
    }

    private void readLoop() {
        try {
            while (true) {
                awaitReadDemand();
                String line = reader.readLine();
                if (line == null) {
                    // 两个消费者都必须能观察到 EOF。
                    publishTerminal(ReadResult.eof());
                    return;
                }
                ReadResult result = ReadResult.line(line);
                routeForCurrentMode().put(result);
            }
        } catch (IOException exception) {
            publishTerminal(ReadResult.failure(exception));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            publishTerminal(ReadResult.failure(new IOException("Console input interrupted", exception)));
        }
    }

    private void awaitReadDemand() throws InterruptedException {
        synchronized (modeLock) {
            while (permissionActive ? permissionWaiters == 0 : userWaiters == 0) {
                modeLock.wait();
            }
        }
    }

    private BlockingQueue<ReadResult> routeForCurrentMode() throws InterruptedException {
        synchronized (modeLock) {
            // 读取期间权限模式可能发生切换。权限提示始终优先；如果它尚未开始等待输入，
            // 暂存已读行直到权限消费者就绪，避免把权限选择发给普通聊天循环。
            while (permissionActive && permissionWaiters == 0) {
                modeLock.wait();
            }
            if (permissionActive) {
                permissionWaiters--;
                return permissionLines;
            }
            while (userWaiters == 0) {
                modeLock.wait();
            }
            userWaiters--;
            return userLines;
        }
    }

    private void publishTerminal(ReadResult result) {
        userLines.offer(result);
        permissionLines.offer(result);
    }

    private static String take(BlockingQueue<ReadResult> queue) throws IOException {
        try {
            ReadResult result = queue.take();
            if (result.failure() != null) {
                throw result.failure();
            }
            return result.endOfInput() ? null : result.line();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for console input", exception);
        }
    }

    private record ReadResult(String line, boolean endOfInput, IOException failure) {
        private static ReadResult line(String line) {
            return new ReadResult(Objects.requireNonNull(line, "line"), false, null);
        }

        private static ReadResult eof() {
            return new ReadResult(null, true, null);
        }

        private static ReadResult failure(IOException failure) {
            return new ReadResult(null, false, Objects.requireNonNull(failure, "failure"));
        }
    }
}
