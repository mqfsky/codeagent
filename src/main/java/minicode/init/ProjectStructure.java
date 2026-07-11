package minicode.init;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * {@code /init} 在当前仓库中检测到的 Java 项目结构。
 *
 * @param projectRoot 项目根目录
 * @param javaProject 是否检测到 Java 源文件或标准 Java 源码目录
 * @param mavenProject 是否检测到 Maven 构建文件
 * @param gradleProject 是否检测到 Gradle 构建文件
 * @param mavenWrapper 是否检测到 Maven Wrapper
 * @param gradleWrapper 是否检测到 Gradle Wrapper
 * @param mainSourceRoots 标准生产源码目录列表
 * @param testSourceRoots 标准测试源码目录列表
 * @param mavenModules 包含 {@code pom.xml} 的模块目录列表
 * @param gradleModules 包含 Gradle 构建文件的模块目录列表
 */
public record ProjectStructure(Path projectRoot,
                               boolean javaProject,
                               boolean mavenProject,
                               boolean gradleProject,
                               boolean mavenWrapper,
                               boolean gradleWrapper,
                               List<Path> mainSourceRoots,
                               List<Path> testSourceRoots,
                               List<Path> mavenModules,
                               List<Path> gradleModules) {
    public ProjectStructure {
        projectRoot = Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
        mainSourceRoots = normalize(mainSourceRoots, "mainSourceRoots");
        testSourceRoots = normalize(testSourceRoots, "testSourceRoots");
        mavenModules = normalize(mavenModules, "mavenModules");
        gradleModules = normalize(gradleModules, "gradleModules");
    }

    private static List<Path> normalize(List<Path> paths, String name) {
        return List.copyOf(Objects.requireNonNull(paths, name).stream()
                .map(path -> Objects.requireNonNull(path, name + " entry").toAbsolutePath().normalize())
                .toList());
    }
}
