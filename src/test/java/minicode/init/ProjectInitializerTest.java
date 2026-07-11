package minicode.init;

import minicode.memory.LayeredMemoryLoader;
import minicode.memory.MemoryDocument;
import minicode.memory.MemorySnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectInitializerTest {
    @TempDir
    Path tempDir;

    @Test
    void initializesMavenJavaProjectAndGeneratedRulesAreImmediatelyLoadable() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("home"));
        Path root = Files.createDirectories(tempDir.resolve("project"));
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.createDirectories(root.resolve("src/main/java"));
        Files.createDirectories(root.resolve("src/test/java"));

        ProjectInitializer.InitReport report = new ProjectInitializer().initialize(root);

        assertTrue(Files.isDirectory(root.resolve(".minicode/rules")));
        assertTrue(Files.isRegularFile(root.resolve("MINI.md")));
        assertTrue(Files.isRegularFile(root.resolve(".minicode/rules/project.md")));
        assertTrue(Files.isRegularFile(root.resolve(".minicode/rules/java.md")));
        assertTrue(Files.isRegularFile(root.resolve(".minicode/rules/maven.md")));
        assertFalse(Files.exists(root.resolve(".minicode/rules/gradle.md")));

        String mini = Files.readString(root.resolve("MINI.md"));
        assertTrue(mini.contains("Language: Java."), mini);
        assertTrue(mini.contains("Build system: Maven."), mini);
        assertTrue(mini.contains("`src/main/java`"), mini);
        assertTrue(mini.contains("`src/test/java`"), mini);
        assertTrue(mini.contains("`mvn test`"), mini);
        assertTrue(report.artifacts().stream()
                .allMatch(artifact -> artifact.status() == ProjectInitializer.InitStatus.CREATED));

        MemorySnapshot memory = new LayeredMemoryLoader().load(home, root);
        assertEquals(List.of("MINI.md", "java.md", "maven.md", "project.md"),
                memory.documents().stream().map(document -> document.path().getFileName().toString()).toList());
        assertEquals(List.of(
                        MemoryDocument.Scope.PROJECT_ROOT,
                        MemoryDocument.Scope.RULE,
                        MemoryDocument.Scope.RULE,
                        MemoryDocument.Scope.RULE),
                memory.documents().stream().map(MemoryDocument::scope).toList());
    }

    @Test
    void secondInitializationSkipsEverythingAndPreservesGeneratedContent() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("project"));
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.createDirectories(root.resolve("src/main/java"));
        ProjectInitializer initializer = new ProjectInitializer();
        initializer.initialize(root);
        String originalMini = Files.readString(root.resolve("MINI.md"));
        String originalJavaRules = Files.readString(root.resolve(".minicode/rules/java.md"));

        ProjectInitializer.InitReport second = initializer.initialize(root.resolve("src/main/java"));

        assertTrue(second.artifacts().stream()
                .allMatch(artifact -> artifact.status() == ProjectInitializer.InitStatus.SKIPPED));
        assertEquals(originalMini, Files.readString(root.resolve("MINI.md")));
        assertEquals(originalJavaRules, Files.readString(root.resolve(".minicode/rules/java.md")));
    }

    @Test
    void neverOverwritesExistingMiniMdOrRuleFiles() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("project"));
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.createDirectories(root.resolve("src/main/java"));
        Files.createDirectories(root.resolve(".minicode/rules"));
        Files.writeString(root.resolve("MINI.md"), "custom mini content");
        Files.writeString(root.resolve(".minicode/rules/java.md"), "custom java rules");

        ProjectInitializer.InitReport report = new ProjectInitializer().initialize(root);

        assertEquals("custom mini content", Files.readString(root.resolve("MINI.md")));
        assertEquals("custom java rules", Files.readString(root.resolve(".minicode/rules/java.md")));
        assertEquals(ProjectInitializer.InitStatus.SKIPPED, status(report, "MINI.md"));
        assertEquals(ProjectInitializer.InitStatus.SKIPPED, status(report, ".minicode/rules/java.md"));
        assertTrue(Files.isRegularFile(root.resolve(".minicode/rules/maven.md")));
        assertTrue(Files.isRegularFile(root.resolve(".minicode/rules/project.md")));
    }

    @Test
    void gradleWrapperIsReflectedInGeneratedVerificationRules() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("gradle-project"));
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'java' }");
        Files.writeString(root.resolve("gradlew"), "wrapper");
        Files.createDirectories(root.resolve("src/main/java"));

        ProjectInitializer.InitReport report = new ProjectInitializer().initialize(root);

        assertTrue(Files.readString(root.resolve("MINI.md")).contains("`./gradlew test`"));
        assertTrue(Files.readString(root.resolve(".minicode/rules/gradle.md")).contains("`./gradlew test`"));
        assertTrue(ProjectInitializer.renderReport(report).contains("Detected         Java, Gradle"));
    }

    private static ProjectInitializer.InitStatus status(ProjectInitializer.InitReport report, String name) {
        return report.artifacts().stream()
                .filter(artifact -> artifact.name().equals(name))
                .findFirst()
                .orElseThrow()
                .status();
    }
}
