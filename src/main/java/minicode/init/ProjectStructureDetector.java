package minicode.init;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 检测 Maven、Gradle 以及标准 Java 源码目录结构。
 */
public final class ProjectStructureDetector {
    private static final int MAX_SCAN_DEPTH = 10;
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(
            ".git", ".gradle", ".idea", ".codeagent", ".mini-code",
            "target", "build", "out", "node_modules"
    );

    /**
     * 从当前工作目录确定项目根目录并扫描项目结构。
     *
     * @param cwd 当前工作目录
     * @return 检测到的项目结构
     */
    public ProjectStructure detect(Path cwd) {
        Path actualCwd = Objects.requireNonNull(cwd, "cwd").toAbsolutePath().normalize();
        Path projectRoot = findProjectRoot(actualCwd);
        DetectionAccumulator detection = new DetectionAccumulator(projectRoot);

        try {
            Files.walkFileTree(projectRoot, Set.of(), MAX_SCAN_DEPTH, detection);
        } catch (IOException | SecurityException ignored) {
            // 扫描采用尽力而为策略；已经收集到的结构仍可用于生成初始化文件。
        }

        return detection.toProjectStructure();
    }

    private static Path findProjectRoot(Path cwd) {
        Path nearestBuildRoot = null;
        for (Path cursor = cwd; cursor != null; cursor = cursor.getParent()) {
            if (nearestBuildRoot == null && containsBuildMarker(cursor)) {
                nearestBuildRoot = cursor;
            }
            if (Files.exists(cursor.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
                return cursor;
            }
        }
        return nearestBuildRoot == null ? cwd : nearestBuildRoot;
    }

    private static boolean containsBuildMarker(Path directory) {
        return Files.isRegularFile(directory.resolve("pom.xml"))
                || Files.isRegularFile(directory.resolve("build.gradle"))
                || Files.isRegularFile(directory.resolve("build.gradle.kts"))
                || Files.isRegularFile(directory.resolve("settings.gradle"))
                || Files.isRegularFile(directory.resolve("settings.gradle.kts"));
    }

    private static final class DetectionAccumulator extends SimpleFileVisitor<Path> {
        private final Path projectRoot;
        private final Set<Path> mainSourceRoots = new HashSet<>();
        private final Set<Path> testSourceRoots = new HashSet<>();
        private final Set<Path> mavenModules = new HashSet<>();
        private final Set<Path> gradleModules = new HashSet<>();
        private boolean javaProject;
        private boolean mavenWrapper;
        private boolean gradleWrapper;

        private DetectionAccumulator(Path projectRoot) {
            this.projectRoot = projectRoot;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
            if (!directory.equals(projectRoot)
                    && SKIPPED_DIRECTORIES.contains(directory.getFileName().toString())) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            if (directory.endsWith(Path.of("src", "main", "java"))) {
                mainSourceRoots.add(directory.toAbsolutePath().normalize());
                javaProject = true;
            } else if (directory.endsWith(Path.of("src", "test", "java"))) {
                testSourceRoots.add(directory.toAbsolutePath().normalize());
                javaProject = true;
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
            if (attributes.isSymbolicLink()) {
                return FileVisitResult.CONTINUE;
            }
            String name = file.getFileName().toString();
            switch (name) {
                case "pom.xml" -> mavenModules.add(file.getParent().toAbsolutePath().normalize());
                case "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts" ->
                        gradleModules.add(file.getParent().toAbsolutePath().normalize());
                case "mvnw", "mvnw.cmd" -> mavenWrapper = true;
                case "gradlew", "gradlew.bat" -> gradleWrapper = true;
                default -> {
                    if (name.endsWith(".java")) {
                        javaProject = true;
                    }
                }
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exception) {
            return FileVisitResult.CONTINUE;
        }

        private ProjectStructure toProjectStructure() {
            List<Path> mainRoots = sorted(mainSourceRoots);
            List<Path> testRoots = sorted(testSourceRoots);
            List<Path> maven = sorted(mavenModules);
            List<Path> gradle = sorted(gradleModules);
            return new ProjectStructure(
                    projectRoot,
                    javaProject || !mainRoots.isEmpty() || !testRoots.isEmpty(),
                    !maven.isEmpty(),
                    !gradle.isEmpty(),
                    mavenWrapper,
                    gradleWrapper,
                    mainRoots,
                    testRoots,
                    maven,
                    gradle
            );
        }

        private List<Path> sorted(Set<Path> paths) {
            List<Path> result = new ArrayList<>(paths);
            result.sort(Comparator.comparing(path -> projectRoot.relativize(path).toString()));
            return List.copyOf(result);
        }
    }
}
