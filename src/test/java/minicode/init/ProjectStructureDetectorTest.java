package minicode.init;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectStructureDetectorTest {
    @TempDir
    Path tempDir;

    @Test
    void detectsGitRootMavenModulesWrapperAndStandardJavaSourceRoots() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("maven-project"));
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.writeString(root.resolve("mvnw"), "#!/bin/sh");
        Path module = Files.createDirectories(root.resolve("service"));
        Files.writeString(module.resolve("pom.xml"), "<project/>");
        Path main = Files.createDirectories(module.resolve("src/main/java"));
        Path tests = Files.createDirectories(module.resolve("src/test/java"));
        Files.writeString(main.resolve("App.java"), "class App {}");

        ProjectStructure result = new ProjectStructureDetector().detect(main);

        assertEquals(root.toAbsolutePath().normalize(), result.projectRoot());
        assertTrue(result.javaProject());
        assertTrue(result.mavenProject());
        assertFalse(result.gradleProject());
        assertTrue(result.mavenWrapper());
        assertEquals(List.of(main.toAbsolutePath().normalize()), result.mainSourceRoots());
        assertEquals(List.of(tests.toAbsolutePath().normalize()), result.testSourceRoots());
        assertEquals(List.of(root.toAbsolutePath().normalize(), module.toAbsolutePath().normalize()),
                result.mavenModules());
    }

    @Test
    void detectsGradleKotlinDslModulesAndWrapper() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("gradle-project"));
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"demo\"");
        Files.writeString(root.resolve("build.gradle.kts"), "plugins { java }");
        Files.writeString(root.resolve("gradlew"), "#!/bin/sh");
        Path module = Files.createDirectories(root.resolve("api"));
        Files.writeString(module.resolve("build.gradle.kts"), "plugins { java }");
        Path main = Files.createDirectories(module.resolve("src/main/java"));

        ProjectStructure result = new ProjectStructureDetector().detect(module);

        assertEquals(root.toAbsolutePath().normalize(), result.projectRoot());
        assertTrue(result.javaProject());
        assertFalse(result.mavenProject());
        assertTrue(result.gradleProject());
        assertTrue(result.gradleWrapper());
        assertEquals(List.of(root.toAbsolutePath().normalize(), module.toAbsolutePath().normalize()),
                result.gradleModules());
        assertEquals(List.of(main.toAbsolutePath().normalize()), result.mainSourceRoots());
    }

    @Test
    void usesNearestBuildRootWithoutGitAndDetectsPlainJavaSources() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("standalone"));
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Path nested = Files.createDirectories(root.resolve("src/main/java/example"));
        Files.writeString(nested.resolve("Main.java"), "package example; class Main {}");

        ProjectStructure result = new ProjectStructureDetector().detect(nested);

        assertEquals(root.toAbsolutePath().normalize(), result.projectRoot());
        assertTrue(result.javaProject());
        assertTrue(result.mavenProject());
    }
}
