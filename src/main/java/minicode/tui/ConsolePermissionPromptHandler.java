package minicode.tui;

import minicode.permissions.model.PermissionDecision;
import minicode.permissions.model.PermissionChoice;
import minicode.permissions.api.PermissionPromptHandler;
import minicode.permissions.model.PermissionPromptResult;
import minicode.permissions.model.PermissionRequest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public final class ConsolePermissionPromptHandler implements PermissionPromptHandler {
    private final BufferedReader input;
    private final PrintWriter output;

    public ConsolePermissionPromptHandler(InputStream input, OutputStream output) {
        this(new BufferedReader(new InputStreamReader(Objects.requireNonNull(input, "input"), StandardCharsets.UTF_8)),
                output);
    }

    public ConsolePermissionPromptHandler(BufferedReader input, OutputStream output) {
        this.input = Objects.requireNonNull(input, "input");
        this.output = new PrintWriter(Objects.requireNonNull(output, "output"), true, StandardCharsets.UTF_8);
    }

    @Override
    public PermissionPromptResult prompt(PermissionRequest request) {
        // 在终端打印选项
        output.println("permission: " + request.details().title());
        output.println(request.details().body());
        for (String fact : request.details().facts()) {
            output.println("  " + fact);
        }
        output.println("Waiting for permission choice. Enter a number, key, [key], or label.");
        renderChoices(request);

        try {
            PermissionChoice choice = readChoice(request);
            // 如果是 deny，且选项包含 feedback，需要输入反馈
            if (choice.requiresFeedback()) {
                output.print("Feedback: ");
                output.flush();
                String feedback = input.readLine();
                if (feedback == null || feedback.isBlank()) {
                    feedback = "Permission denied by user";
                }
                return PermissionPromptResult.deny(choice.key(), choice.decision(), feedback);
            }
            if (isAllow(choice.decision())) {
                return PermissionPromptResult.allow(choice.key(), choice.decision());
            }
            return PermissionPromptResult.deny(choice.key(), choice.decision(), null);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private PermissionChoice readChoice(PermissionRequest request) throws IOException {
        output.print("Permission choice: ");
        output.flush();
        while (true) {
            String line = input.readLine();
            if (line == null) {
                // 选择兜底选项
                return fallbackDenyChoice(request);
            }
            // 匹配用户输入
            Optional<PermissionChoice> selected = selectChoice(request, line);
            if (selected.isPresent()) {
                return selected.orElseThrow();
            }
            // 无法识别，则让用户重新输入一遍
            output.println("Unknown permission choice: " + line.trim()
                    + ". Enter one of the listed numbers, keys, [keys], or labels.");
            output.print("Permission choice: ");
            output.flush();
        }
    }

    /**
     * 1) Allow once [allow_once]
     * 2) Allow for this turn [allow_turn]
     * 3) Deny once [deny_once]
     * 4) Deny with feedback [deny_feedback]
     * @param request
     */
    private void renderChoices(PermissionRequest request) {
        for (int index = 0; index < request.choices().size(); index++) {
            PermissionChoice choice = request.choices().get(index);
            output.println("  " + (index + 1) + ") " + choice.label() + " [" + choice.key() + "]");
        }
    }

    /**
     * 兜底选择，先选择 DENY_ONCE，没找到择选择任意拒绝相信，还没找到，则选最后一个选项
     * @param request
     * @return
     */
    private static PermissionChoice fallbackDenyChoice(PermissionRequest request) {
        return request.choices().stream()
                .filter(choice -> choice.decision() == PermissionDecision.DENY_ONCE)
                .findFirst()
                .or(() -> request.choices().stream().filter(choice -> !isAllow(choice.decision())).findFirst())
                .orElseGet(() -> request.choices().getLast());
    }

    /**
     * 将用户在终端输入的一行文字，匹配成某个 PermissionChoice，支持 3 种格式：
     * 1                 // 序号
     * allow_once        // key
     * Allow once        // label
     * @param request
     * @param line
     * @return
     */
    private static Optional<PermissionChoice> selectChoice(PermissionRequest request, String line) {
        if (line == null) {
            return Optional.empty();
        }
        String normalized = normalize(line);
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        // 先匹配序号
        try {
            int choiceNumber = Integer.parseInt(normalized);
            if (choiceNumber >= 1 && choiceNumber <= request.choices().size()) {
                return Optional.of(request.choices().get(choiceNumber - 1));
            }
        } catch (NumberFormatException ignored) {
            // 如果输入不是数字，则会抛出异常并被捕获，这里不做处理
            // Continue with key/label matching.
        }
        for (PermissionChoice choice : request.choices()) {
            // 匹配 key 或 label
            if (normalize(choice.key()).equals(normalized) || normalize(choice.label()).equals(normalized)) {
                return Optional.of(choice);
            }
        }
        return Optional.empty();
    }

    /**
     * 去掉首尾空格
     * 去掉外层方括号
     * 连续空格合并
     * 转换成小写
     */
    private static String normalize(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length() > 2) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static boolean isAllow(PermissionDecision decision) {
        return decision == PermissionDecision.ALLOW_ONCE
                || decision == PermissionDecision.ALLOW_TURN
                || decision == PermissionDecision.ALLOW_ALWAYS;
    }
}
