package minicode.tools.result;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 后台任务启动后的返回信息。
 *
 * @param taskId 后台任务 id
 * @param type 类型或事件类型
 * @param command 命令字符串
 * @param cwd 当前 workspace 工作目录
 * @param pid 后台进程 id
 * @param status 后台任务状态
 * @param startedAt 后台任务开始时间
 * @param endedAt 后台任务结束时间；为空表示仍在运行
 * @param exitCode 命令退出码
 * @param outputRef 后台任务输出存储引用；为空表示没有输出文件
 * @param errorSummary 错误摘要；为空表示没有错误摘要
 */
public record BackgroundTaskResult(String taskId, BackgroundTaskType type, String command, String cwd,
                                   Optional<Long> pid, BackgroundTaskStatus status, Instant startedAt,
                                   Optional<Instant> endedAt, Optional<Integer> exitCode,
                                   Optional<String> outputRef, Optional<String> errorSummary) {
    public BackgroundTaskResult {
        requireText(taskId, "taskId");
        type = Objects.requireNonNull(type, "type");
        requireText(command, "command");
        requireText(cwd, "cwd");
        pid = Objects.requireNonNull(pid, "pid");
        status = Objects.requireNonNull(status, "status");
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
        endedAt = Objects.requireNonNull(endedAt, "endedAt");
        exitCode = Objects.requireNonNull(exitCode, "exitCode");
        outputRef = Objects.requireNonNull(outputRef, "outputRef");
        errorSummary = Objects.requireNonNull(errorSummary, "errorSummary");
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
