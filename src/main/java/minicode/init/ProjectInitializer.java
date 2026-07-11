package minicode.init;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * 根据检测到的 Java 项目结构幂等生成 MiniCode 项目记忆文件。
 */
public final class ProjectInitializer {
    private final ProjectStructureDetector detector;

    public ProjectInitializer() {
        this(new ProjectStructureDetector());
    }

    ProjectInitializer(ProjectStructureDetector detector) {
        this.detector = Objects.requireNonNull(detector, "detector");
    }

    /**
     * 初始化当前项目。已经存在的目录和文件会被跳过，用户内容不会被覆盖。
     *
     * @param cwd 当前工作目录
     * @return 初始化结果和项目结构检测信息
     */
    public InitReport initialize(Path cwd) {
        ProjectStructure structure = detector.detect(cwd);
        Path projectRoot = structure.projectRoot();
        try {
            Path boundary = projectRoot.toRealPath();
            List<InitArtifact> artifacts = new ArrayList<>();
            Path memoryDirectory = projectRoot.resolve(".minicode");
            Path rulesDirectory = memoryDirectory.resolve("rules");

            // 先建立目录，再以 CREATE_NEW 写入文件，确保并发或重复执行时不会覆盖已有内容。
            artifacts.add(new InitArtifact(".minicode/", ensureDirectory(memoryDirectory, boundary)));
            artifacts.add(new InitArtifact(".minicode/rules/", ensureDirectory(rulesDirectory, boundary)));
            artifacts.add(new InitArtifact("MINI.md",
                    writeFileIfMissing(projectRoot.resolve("MINI.md"), renderMiniMd(structure), boundary)));
            artifacts.add(new InitArtifact(".minicode/rules/project.md",
                    writeFileIfMissing(rulesDirectory.resolve("project.md"), renderProjectRules(), boundary)));

            if (structure.javaProject()) {
                artifacts.add(new InitArtifact(".minicode/rules/java.md",
                        writeFileIfMissing(rulesDirectory.resolve("java.md"), renderJavaRules(), boundary)));
            }
            if (structure.mavenProject()) {
                artifacts.add(new InitArtifact(".minicode/rules/maven.md",
                        writeFileIfMissing(rulesDirectory.resolve("maven.md"), renderMavenRules(structure), boundary)));
            }
            if (structure.gradleProject()) {
                artifacts.add(new InitArtifact(".minicode/rules/gradle.md",
                        writeFileIfMissing(rulesDirectory.resolve("gradle.md"), renderGradleRules(structure), boundary)));
            }
            return new InitReport(projectRoot, structure, artifacts);
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot initialize MiniCode project at " + projectRoot, exception);
        }
    }

    /**
     * 将初始化结果渲染为两套 TUI 都可以直接展示的文本。
     */
    public static String renderReport(InitReport report) {
        Objects.requireNonNull(report, "report");
        StringJoiner rendered = new StringJoiner("\n");
        rendered.add("Init");
        rendered.add("  Project          " + report.projectRoot());
        rendered.add("  Detected         " + detectedSummary(report.structure()));
        for (InitArtifact artifact : report.artifacts()) {
            String label = artifact.status() == InitStatus.CREATED
                    ? "created"
                    : "skipped (already exists)";
            rendered.add("  " + padRight(artifact.name(), 24) + label);
        }
        rendered.add("  Next step        Review and tailor MINI.md and .minicode/rules/*.md");
        return rendered.toString();
    }

    static String renderMiniMd(ProjectStructure structure) {
        StringJoiner content = new StringJoiner("\n");
        content.add("# MINI.md");
        content.add("");
        content.add("This file provides guidance to MiniCode when working with this repository.");
        content.add("");
        content.add("## Detected project");
        content.add("- Language: " + (structure.javaProject() ? "Java." : "No Java source markers detected yet."));
        content.add("- Build system: " + buildSystemSummary(structure) + ".");
        content.add("");
        content.add("## Repository structure");
        appendPaths(content, "Main source roots", structure, structure.mainSourceRoots());
        appendPaths(content, "Test source roots", structure, structure.testSourceRoots());
        appendPaths(content, "Maven modules", structure, structure.mavenModules());
        appendPaths(content, "Gradle modules", structure, structure.gradleModules());
        content.add("");
        content.add("## Verification");
        List<String> verificationCommands = verificationCommands(structure);
        for (String command : verificationCommands) {
            content.add("- Run `" + command + "` before shipping behavior changes.");
        }
        if (verificationCommands.isEmpty()) {
            content.add("- Document the project's build and test commands once its structure is established.");
        }
        content.add("");
        content.add("## Layered rules");
        content.add("- Additional project rules are stored in `.minicode/rules/*.md` and loaded in filename order.");
        content.add("- Keep generated guidance aligned with the repository's actual build and test workflow.");
        content.add("");
        content.add("## Working agreement");
        content.add("- Prefer small, reviewable changes and avoid unrelated rewrites.");
        content.add("- Update production code and its tests together when behavior changes.");
        content.add("- Do not overwrite existing MINI.md or rule files automatically; edit them intentionally.");
        content.add("");
        return content.toString();
    }

    private static String renderProjectRules() {
        return """
                # Project rules

                - Preserve the existing module and package boundaries.
                - Keep changes focused, reviewable, and consistent with nearby code.
                - Update relevant tests whenever behavior changes.
                - Use the detected verification commands documented in the repository MINI.md.
                """.strip() + "\n";
    }

    private static String renderJavaRules() {
        return """
                # Java rules

                - Follow the repository's existing package, naming, and API conventions.
                - Use the Java version configured by the active Maven or Gradle build.
                - Prefer focused unit tests for new behavior and regression fixes.
                - Keep resource lifecycles and exception boundaries explicit.
                """.strip() + "\n";
    }

    private static String renderMavenRules(ProjectStructure structure) {
        String command = structure.mavenWrapper() ? "./mvnw test" : "mvn test";
        return """
                # Maven rules

                - Treat pom.xml as the source of truth for dependencies, plugins, and the Java release.
                - Run `%s` before completing behavior changes.
                - Keep multi-module changes scoped to the affected modules whenever possible.
                """.formatted(command).strip() + "\n";
    }

    private static String renderGradleRules(ProjectStructure structure) {
        String command = structure.gradleWrapper() ? "./gradlew test" : "gradle test";
        return """
                # Gradle rules

                - Treat Gradle build files and version catalogs as the source of truth for the build.
                - Run `%s` before completing behavior changes.
                - Keep multi-module changes scoped to the affected modules whenever possible.
                """.formatted(command).strip() + "\n";
    }

    private static InitStatus ensureDirectory(Path directory, Path boundary) throws IOException {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Cannot initialize: " + directory + " exists and is not a safe directory");
            }
            ensureWithinBoundary(directory.toRealPath(), boundary);
            return InitStatus.SKIPPED;
        }
        try {
            Files.createDirectory(directory);
            ensureWithinBoundary(directory.toRealPath(), boundary);
            return InitStatus.CREATED;
        } catch (FileAlreadyExistsException exception) {
            if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(directory)) {
                ensureWithinBoundary(directory.toRealPath(), boundary);
                return InitStatus.SKIPPED;
            }
            throw exception;
        }
    }

    private static InitStatus writeFileIfMissing(Path file, String content, Path boundary) throws IOException {
        Path parent = file.getParent().toRealPath();
        ensureWithinBoundary(parent, boundary);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Cannot initialize: " + file + " exists and is not a safe regular file");
            }
            return InitStatus.SKIPPED;
        }
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return InitStatus.CREATED;
        } catch (FileAlreadyExistsException exception) {
            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file)) {
                return InitStatus.SKIPPED;
            }
            throw exception;
        }
    }

    private static void ensureWithinBoundary(Path path, Path boundary) throws IOException {
        if (!path.startsWith(boundary)) {
            throw new IOException("Cannot initialize outside project root: " + path);
        }
    }

    private static void appendPaths(StringJoiner content, String label, ProjectStructure structure, List<Path> paths) {
        if (paths.isEmpty()) {
            content.add("- " + label + ": none detected.");
            return;
        }
        content.add("- " + label + ": " + paths.stream()
                .map(path -> "`" + relativePath(structure.projectRoot(), path) + "`")
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow() + ".");
    }

    private static List<String> verificationCommands(ProjectStructure structure) {
        List<String> commands = new ArrayList<>();
        if (structure.mavenProject()) {
            commands.add(structure.mavenWrapper() ? "./mvnw test" : "mvn test");
        }
        if (structure.gradleProject()) {
            commands.add(structure.gradleWrapper() ? "./gradlew test" : "gradle test");
        }
        return List.copyOf(commands);
    }

    private static String detectedSummary(ProjectStructure structure) {
        List<String> detected = new ArrayList<>();
        if (structure.javaProject()) {
            detected.add("Java");
        }
        if (structure.mavenProject()) {
            detected.add("Maven");
        }
        if (structure.gradleProject()) {
            detected.add("Gradle");
        }
        return detected.isEmpty() ? "generic project" : String.join(", ", detected);
    }

    private static String buildSystemSummary(ProjectStructure structure) {
        List<String> systems = new ArrayList<>();
        if (structure.mavenProject()) {
            systems.add("Maven");
        }
        if (structure.gradleProject()) {
            systems.add("Gradle");
        }
        return systems.isEmpty() ? "none detected" : String.join(" and ", systems);
    }

    private static String relativePath(Path root, Path path) {
        String relative = root.relativize(path).toString().replace('\\', '/');
        return relative.isBlank() ? "." : relative;
    }

    private static String padRight(String value, int width) {
        return value.length() >= width ? value + " " : value + " ".repeat(width - value.length());
    }

    public enum InitStatus {
        CREATED,
        SKIPPED
    }

    public record InitArtifact(String name, InitStatus status) {
        public InitArtifact {
            if (Objects.requireNonNull(name, "name").isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
            status = Objects.requireNonNull(status, "status");
        }
    }

    public record InitReport(Path projectRoot, ProjectStructure structure, List<InitArtifact> artifacts) {
        public InitReport {
            projectRoot = Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
            structure = Objects.requireNonNull(structure, "structure");
            artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
        }
    }
}
