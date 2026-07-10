package minicode.tools.builtin;

import minicode.permissions.model.CommandClassification;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class CommandClassifier {
    private static final Set<String> READONLY_EXECUTABLES = Set.of(
            "pwd", "ls", "dir", "cat", "type", "git", "mvn", "java", "node", "npm"
    );
    private static final Set<String> DEVELOPMENT_WORDS = Set.of(
            "test", "build", "compile", "package", "verify", "lint", "format", "fmt", "check"
    );
    private static final Set<String> DANGEROUS_EXECUTABLES = Set.of(
            "rm", "del", "erase", "rmdir", "rd", "mv", "move", "cp", "copy", "chmod", "chown",
            "sudo", "su", "shutdown", "reboot", "mkfs", "diskpart", "reg", "sc"
    );
    private static final Set<String> DANGEROUS_WORDS = Set.of(
            "delete", "remove", "clean", "prune", "reset", "checkout", "install", "uninstall", "publish", "force"
    );

    private final ShellSnippetPolicy shellSnippetPolicy;

    public CommandClassifier() {
        this(new ShellSnippetPolicy());
    }

    public CommandClassifier(ShellSnippetPolicy shellSnippetPolicy) {
        this.shellSnippetPolicy = shellSnippetPolicy;
    }

    public CommandClassificationResult classify(String command, List<String> args) {
        // 是否是 shell 指令
        // sh -c "rm -rf target"
        // bash -lc "..."
        // powershell -Command "..."
        if (shellSnippetPolicy.looksLikeShellSnippet(command, args)) {
            return new CommandClassificationResult(CommandClassification.SENSITIVE, true, "shell snippet detected");
        }

        // 提取指令名称：如果传的是 /usr/bin/git，提取 git，同时如果存在后缀则去掉
        String executable = executableName(command);
        // 归一化 args，参数转小写，trim
        List<String> normalizedArgs = args.stream().map(CommandClassifier::normalizeToken).toList();

        // 判断是否为危险命令
        if (DANGEROUS_EXECUTABLES.contains(executable) || containsDangerousWord(normalizedArgs)) {
            return new CommandClassificationResult(CommandClassification.DANGEROUS, false, "dangerous command");
        }
        // 判断是否为只读命令
        if (isReadonly(executable, normalizedArgs)) {
            return new CommandClassificationResult(CommandClassification.READONLY, false, "readonly command");
        }
        // 判断开发类命令
        if (containsAny(normalizedArgs, DEVELOPMENT_WORDS)) {
            return new CommandClassificationResult(CommandClassification.DEVELOPMENT, false, "development command");
        }
        return new CommandClassificationResult(CommandClassification.UNKNOWN, false, "unknown command");
    }

    private static boolean isReadonly(String executable, List<String> args) {
        if (!READONLY_EXECUTABLES.contains(executable)) {
            return false;
        }
        if (executable.equals("pwd")) {
            return true;
        }
        if (executable.equals("ls") || executable.equals("dir")) {
            return args.isEmpty();
        }
        if (executable.equals("cat") || executable.equals("type")) {
            return false;
        }
        if (executable.equals("git")) {
            return args.equals(List.of("status"))
                    || args.equals(List.of("diff"))
                    || args.equals(List.of("log"))
                    || args.equals(List.of("show"));
        }
        if (executable.equals("mvn")) {
            return args.equals(List.of("-version")) || args.equals(List.of("--version"));
        }
        if (executable.equals("java") || executable.equals("node") || executable.equals("npm")) {
            return args.equals(List.of("-version")) || args.equals(List.of("--version")) || args.equals(List.of("version"));
        }
        return false;
    }

    private static boolean containsAny(List<String> args, Set<String> words) {
        return args.stream().anyMatch(words::contains);
    }

    private static boolean containsDangerousWord(List<String> args) {
        return args.stream().anyMatch(arg -> DANGEROUS_WORDS.stream().anyMatch(arg::contains));
    }

    private static String executableName(String command) {
        String name;
        try {
            Path fileName = Path.of(command).getFileName();
            name = fileName == null ? command : fileName.toString();
        } catch (RuntimeException ignored) {
            name = command;
        }
        String normalized = normalizeToken(name);
        if (normalized.endsWith(".exe")) {
            return normalized.substring(0, normalized.length() - 4);
        }
        if (normalized.endsWith(".cmd") || normalized.endsWith(".bat")) {
            return normalized.substring(0, normalized.length() - 4);
        }
        return normalized;
    }

    private static String normalizeToken(String value) {
        return value.toLowerCase(Locale.ROOT).trim();
    }
}
