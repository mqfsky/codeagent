package minicode.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 发现当前工作区的分层指令文件，并按照配置的预算限制处理其内容。
 *
 * <p>加载过程采用尽力而为策略：缺失、空白、不可读或在读取期间被删除的文件会被跳过，
 * 避免项目记忆文件的问题阻止 MiniCode 启动。</p>
 */
public final class LayeredMemoryLoader {
    public static final int DEFAULT_MAX_PER_FILE_CHARS = 8_000;
    public static final int DEFAULT_MAX_TOTAL_CHARS = 20_000;

    static final String TRUNCATION_MARKER = "\n\n[truncated]";
    private static final Pattern INCLUDE_LINE_PATTERN = Pattern.compile("^@([^\\s]+)\\s*$");
    private static final Pattern WINDOWS_DRIVE_PATH_PATTERN = Pattern.compile("^[A-Za-z]:.*");
    private static final List<Path> GLOBAL_CANDIDATES = List.of(
            Path.of("AGENTS.md"),
            Path.of("MINI.md")
    );
    private static final List<Path> DIRECTORY_CANDIDATES = List.of(
            Path.of("AGENTS.md"),
            Path.of("MINI.md"),
            Path.of(".minicode", "MINI.md"),
            Path.of(".mini-code", "MINI.md"),
            Path.of("AGENTS.local.md"),
            Path.of("MINI.local.md")
    );
    private static final List<Path> RULE_DIRECTORIES = List.of(
            Path.of(".minicode", "rules"),
            Path.of(".mini-code", "rules")
    );

    private final int maxPerFileChars;
    private final int maxTotalChars;

    public LayeredMemoryLoader() {
        this(DEFAULT_MAX_PER_FILE_CHARS, DEFAULT_MAX_TOTAL_CHARS);
    }

    public LayeredMemoryLoader(int maxPerFileChars, int maxTotalChars) {
        if (maxPerFileChars <= 0) {
            throw new IllegalArgumentException("maxPerFileChars must be positive");
        }
        if (maxTotalChars <= 0) {
            throw new IllegalArgumentException("maxTotalChars must be positive");
        }
        this.maxPerFileChars = maxPerFileChars;
        this.maxTotalChars = maxTotalChars;
    }

    public MemorySnapshot load(Path home, Path cwd) {
        Path actualHome = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        Path actualCwd = Objects.requireNonNull(cwd, "cwd").toAbsolutePath().normalize();
        Path projectRoot = findProjectRoot(actualCwd);

        List<MemoryDocument> discovered = new ArrayList<>();
        discoverCandidates(actualHome, GLOBAL_CANDIDATES, MemoryDocument.Scope.GLOBAL, 0, discovered);

        List<Path> projectDirectories = projectDirectories(projectRoot, actualCwd);
        for (int depth = 0; depth < projectDirectories.size(); depth++) {
            Path directory = projectDirectories.get(depth);
            MemoryDocument.Scope scope = depth == 0
                    ? MemoryDocument.Scope.PROJECT_ROOT
                    : MemoryDocument.Scope.SUBDIRECTORY;
            discoverCandidates(directory, DIRECTORY_CANDIDATES, scope, depth, discovered);
            discoverRuleFiles(directory, depth, discovered);
        }

        if (discovered.isEmpty()) {
            return MemorySnapshot.empty();
        }
        return applyBudgets(deduplicateKeepingClosest(discovered));
    }

    private void discoverCandidates(Path base, List<Path> candidates, MemoryDocument.Scope scope, int depth,
                                    List<MemoryDocument> target) {
        java.util.Optional<Path> boundary = realPath(base);
        if (boundary.isEmpty()) {
            return;
        }
        for (Path candidate : candidates) {
            Path file = base.resolve(candidate).toAbsolutePath().normalize();
            readMemoryFile(file, boundary.orElseThrow()).ifPresent(content ->
                    target.add(new MemoryDocument(file, scope, depth, content, false)));
        }
    }

    private void discoverRuleFiles(Path base, int depth, List<MemoryDocument> target) {
        java.util.Optional<Path> boundary = realPath(base);
        if (boundary.isEmpty()) {
            return;
        }
        for (Path relativeDirectory : RULE_DIRECTORIES) {
            Path rulesDirectory = base.resolve(relativeDirectory).toAbsolutePath().normalize();
            java.util.Optional<Path> realRulesDirectory = realPath(rulesDirectory);
            if (realRulesDirectory.isEmpty() || !realRulesDirectory.orElseThrow().startsWith(boundary.orElseThrow())) {
                continue;
            }
            try (var files = Files.list(rulesDirectory)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".md"))
                        .sorted(java.util.Comparator.comparing(path -> path.getFileName().toString()))
                        .forEach(path -> readMemoryFile(path.toAbsolutePath().normalize(), boundary.orElseThrow())
                                .ifPresent(content -> target.add(new MemoryDocument(path, MemoryDocument.Scope.RULE,
                                        depth, content, false))));
            } catch (IOException | SecurityException ignored) {
                // 规则目录和普通记忆文件一样采用尽力而为加载策略。
            }
        }
    }

    private static java.util.Optional<String> readMemoryFile(Path file, Path boundary) {
        try {
            if (!Files.isRegularFile(file)) {
                return java.util.Optional.empty();
            }
            Path realFile = file.toRealPath();
            if (!realFile.startsWith(boundary)) {
                return java.util.Optional.empty();
            }
            String content = readNonBlank(realFile).orElse(null);
            if (content == null) {
                return java.util.Optional.empty();
            }
            Set<Path> visited = new HashSet<>();
            visited.add(realFile);
            return java.util.Optional.of(resolveIncludes(content, file, boundary, visited).strip());
        } catch (IOException | SecurityException ignored) {
            return java.util.Optional.empty();
        }
    }

    private static String resolveIncludes(String content, Path fromFile, Path boundary, Set<Path> visited) {
        List<String> rendered = new ArrayList<>();
        for (String line : content.split("\\R", -1)) {
            Matcher matcher = INCLUDE_LINE_PATTERN.matcher(line.strip());
            if (!matcher.matches()) {
                rendered.add(line);
                continue;
            }

            String includeReference = matcher.group(1);
            if (isUnsafeIncludeReference(includeReference)) {
                rendered.add(includeMarker("unsafe path", includeReference));
                continue;
            }

            Path includePath;
            try {
                includePath = fromFile.getParent()
                        .resolve(includeReference.replace('\\', '/'))
                        .toAbsolutePath()
                        .normalize();
            } catch (InvalidPathException exception) {
                rendered.add(includeMarker("unsafe path", includeReference));
                continue;
            }

            Path realInclude;
            try {
                if (!Files.isRegularFile(includePath)) {
                    rendered.add(includeMarker("not found", includeReference));
                    continue;
                }
                realInclude = includePath.toRealPath();
            } catch (IOException | SecurityException exception) {
                rendered.add(includeMarker("not found", includeReference));
                continue;
            }

            // 真实路径必须仍位于顶层记忆文件的安全边界内，避免通过符号链接读取外部文件。
            if (!realInclude.startsWith(boundary)) {
                rendered.add(includeMarker("unsafe path", includeReference));
                continue;
            }
            if (!visited.add(realInclude)) {
                rendered.add(includeMarker("cycle detected", includeReference));
                continue;
            }

            try {
                String included = readNonBlank(realInclude).orElse(null);
                if (included == null) {
                    rendered.add(includeMarker("not found", includeReference));
                    continue;
                }
                rendered.add("<!-- included from " + includeReference + " -->");
                rendered.add(resolveIncludes(included, includePath, boundary, visited));
                rendered.add("<!-- end include " + includeReference + " -->");
            } finally {
                visited.remove(realInclude);
            }
        }
        return String.join("\n", rendered);
    }

    private static boolean isUnsafeIncludeReference(String includeReference) {
        if (includeReference.isBlank()
                || includeReference.startsWith("/")
                || includeReference.startsWith("\\")
                || WINDOWS_DRIVE_PATH_PATTERN.matcher(includeReference).matches()) {
            return true;
        }
        String portableReference = includeReference.replace('\\', '/');
        try {
            Path path = Path.of(portableReference);
            if (path.isAbsolute()) {
                return true;
            }
            for (Path segment : path) {
                if (segment.toString().equals("..")) {
                    return true;
                }
            }
            return false;
        } catch (InvalidPathException exception) {
            return true;
        }
    }

    private static String includeMarker(String reason, String includeReference) {
        return "<!-- include skipped: " + reason + " " + includeReference + " -->";
    }

    private static java.util.Optional<String> readNonBlank(Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8).strip();
            return content.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(content);
        } catch (IOException | SecurityException ignored) {
            return java.util.Optional.empty();
        }
    }

    private static java.util.Optional<Path> realPath(Path path) {
        try {
            return java.util.Optional.of(path.toRealPath());
        } catch (IOException | SecurityException exception) {
            return java.util.Optional.empty();
        }
    }

    private static Path findProjectRoot(Path cwd) {
        for (Path cursor = cwd; cursor != null; cursor = cursor.getParent()) {
            if (Files.exists(cursor.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
                return cursor;
            }
        }
        return cwd;
    }

    private static List<Path> projectDirectories(Path projectRoot, Path cwd) {
        if (!cwd.startsWith(projectRoot)) {
            return List.of(cwd);
        }
        List<Path> directories = new ArrayList<>();
        Path cursor = cwd;
        while (cursor != null) {
            directories.add(cursor);
            if (cursor.equals(projectRoot)) {
                break;
            }
            cursor = cursor.getParent();
        }
        Collections.reverse(directories);
        return List.copyOf(directories);
    }

    private static List<MemoryDocument> deduplicateKeepingClosest(List<MemoryDocument> documents) {
        Set<String> seenHashes = new HashSet<>();
        List<MemoryDocument> reverseUnique = new ArrayList<>();
        for (int index = documents.size() - 1; index >= 0; index--) {
            MemoryDocument document = documents.get(index);
            if (seenHashes.add(sha256(document.content()))) {
                reverseUnique.add(document);
            }
        }
        Collections.reverse(reverseUnique);
        return List.copyOf(reverseUnique);
    }

    private MemorySnapshot applyBudgets(List<MemoryDocument> documents) {
        List<MemoryDocument> perFileBudgeted = new ArrayList<>();
        boolean anyTruncated = false;
        for (MemoryDocument document : documents) {
            String content = fit(document.content(), maxPerFileChars);
            if (content.isEmpty()) {
                anyTruncated = true;
                continue;
            }
            boolean truncated = content.length() < document.content().length();
            anyTruncated = anyTruncated || truncated;
            perFileBudgeted.add(new MemoryDocument(document.path(), document.scope(), document.depth(), content,
                    truncated));
        }

        int remaining = maxTotalChars;
        List<MemoryDocument> reverseSelected = new ArrayList<>();
        for (int index = perFileBudgeted.size() - 1; index >= 0; index--) {
            MemoryDocument document = perFileBudgeted.get(index);
            String content = fit(document.content(), remaining);
            if (content.isEmpty()) {
                anyTruncated = true;
                continue;
            }
            boolean truncated = document.truncated() || content.length() < document.content().length();
            anyTruncated = anyTruncated || truncated;
            reverseSelected.add(new MemoryDocument(document.path(), document.scope(), document.depth(), content,
                    truncated));
            remaining -= content.length();
        }
        Collections.reverse(reverseSelected);
        int total = reverseSelected.stream().mapToInt(document -> document.content().length()).sum();
        return reverseSelected.isEmpty()
                ? new MemorySnapshot(List.of(), 0, anyTruncated)
                : new MemorySnapshot(reverseSelected, total, anyTruncated);
    }

    private static String fit(String content, int budget) {
        if (budget <= 0) {
            return "";
        }
        if (content.length() <= budget) {
            return content;
        }
        if (budget < TRUNCATION_MARKER.length()) {
            return "";
        }
        int prefixLength = budget - TRUNCATION_MARKER.length();
        return content.substring(0, prefixLength) + TRUNCATION_MARKER;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.strip().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
