package minicode.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayeredMemoryLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsGlobalProjectAndNestedMemoriesInBroadToSpecificOrder() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("home"));
        Path projectRoot = Files.createDirectories(tempDir.resolve("project"));
        Files.createDirectories(projectRoot.resolve(".git"));
        Path module = Files.createDirectories(projectRoot.resolve("modules/api"));

        Path global = write(home, "AGENTS.md", "global-memory-content-marker");
        Path project = write(projectRoot, "AGENTS.md", "project-memory-content-marker");
        Path modules = write(projectRoot.resolve("modules"), "AGENTS.md", "modules-memory-content-marker");
        Path api = write(module, "AGENTS.md", "api-memory-content-marker");

        LayeredMemoryLoader loader = new LayeredMemoryLoader();
        MemorySnapshot first = loader.load(home, module);
        MemorySnapshot second = loader.load(home, module);

        assertEquals(List.of(global, project, modules, api), paths(first.documents()));
        assertEquals(paths(first.documents()), paths(second.documents()));
        assertEquals(
                List.of(
                        MemoryDocument.Scope.GLOBAL,
                        MemoryDocument.Scope.PROJECT_ROOT,
                        MemoryDocument.Scope.SUBDIRECTORY,
                        MemoryDocument.Scope.SUBDIRECTORY
                ),
                first.documents().stream().map(MemoryDocument::scope).toList()
        );
        assertEquals(List.of(0, 0, 1, 2),
                first.documents().stream().map(MemoryDocument::depth).toList());
        assertEquals(List.of("global-memory-content-marker", "project-memory-content-marker",
                        "modules-memory-content-marker", "api-memory-content-marker"),
                first.documents().stream().map(MemoryDocument::content).toList());

        String rendered = first.renderPromptSection();
        assertAppearsBefore(rendered, "global-memory-content-marker", "project-memory-content-marker");
        assertAppearsBefore(rendered, "project-memory-content-marker", "modules-memory-content-marker");
        assertAppearsBefore(rendered, "modules-memory-content-marker", "api-memory-content-marker");
    }

    @Test
    void usesStableCandidateOrderWithinEveryDirectory() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("home"));
        Path projectRoot = Files.createDirectories(tempDir.resolve("project"));
        Files.createDirectories(projectRoot.resolve(".git"));
        Path module = Files.createDirectories(projectRoot.resolve("module"));

        Path rootAgents = write(projectRoot, "AGENTS.md", "root agents");
        Path rootMini = write(projectRoot, "CODEAGENT.md", "root mini");
        Path moduleAgents = write(module, "AGENTS.md", "module agents");
        Path moduleMini = write(module, "CODEAGENT.md", "module mini");

        MemorySnapshot snapshot = new LayeredMemoryLoader().load(home, module);

        assertEquals(List.of(rootAgents, rootMini, moduleAgents, moduleMini), paths(snapshot.documents()));
        assertEquals(List.of("root agents", "root mini", "module agents", "module mini"),
                snapshot.documents().stream().map(MemoryDocument::content).toList());
    }

    @Test
    void loadsMinicodeRuleFilesAfterDirectoryMemoryInFilenameOrder() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("home"));
        Path projectRoot = Files.createDirectories(tempDir.resolve("project"));
        Files.createDirectories(projectRoot.resolve(".git"));
        Path agents = write(projectRoot, "AGENTS.md", "root agents");
        Path firstRule = write(projectRoot, ".codeagent/rules/a-java.md", "java rules");
        Path secondRule = write(projectRoot, ".codeagent/rules/z-testing.md", "testing rules");
        write(projectRoot, ".codeagent/rules/ignored.txt", "not a rule");

        MemorySnapshot snapshot = new LayeredMemoryLoader().load(home, projectRoot);

        assertEquals(List.of(agents, firstRule, secondRule), paths(snapshot.documents()));
        assertEquals(List.of(
                        MemoryDocument.Scope.PROJECT_ROOT,
                        MemoryDocument.Scope.RULE,
                        MemoryDocument.Scope.RULE),
                snapshot.documents().stream().map(MemoryDocument::scope).toList());
        assertTrue(snapshot.renderReport(projectRoot).contains("scope: rules"));
    }

    @Test
    void deduplicatesByContentHashAndKeepsTheClosestOccurrence() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("home"));
        Path projectRoot = Files.createDirectories(tempDir.resolve("project"));
        Files.createDirectories(projectRoot.resolve(".git"));
        Path module = Files.createDirectories(projectRoot.resolve("module"));
        String duplicate = "the same instruction at every level";

        write(home, "AGENTS.md", duplicate);
        write(projectRoot, "AGENTS.md", duplicate);
        Path projectOnly = write(projectRoot, "CODEAGENT.md", "project-only instruction");
        Path closestDuplicate = write(module, "AGENTS.md", duplicate);
        Path moduleOnly = write(module, "CODEAGENT.md", "module-only instruction");

        MemorySnapshot snapshot = new LayeredMemoryLoader().load(home, module);

        assertEquals(List.of(projectOnly, closestDuplicate, moduleOnly), paths(snapshot.documents()));
        assertEquals(1, snapshot.documents().stream()
                .filter(document -> document.content().equals(duplicate))
                .count());
        assertEquals(closestDuplicate.toAbsolutePath().normalize(), snapshot.documents().get(1).path());
    }

    @Test
    void truncatesSingleFileAtTheExactPerFileBudgetAndAppendsMarker() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("home"));
        Path projectRoot = Files.createDirectories(tempDir.resolve("project"));
        Files.createDirectories(projectRoot.resolve(".git"));
        write(projectRoot, "AGENTS.md", "x".repeat(200));
        int perFileBudget = 48;

        MemorySnapshot snapshot = new LayeredMemoryLoader(perFileBudget, 200).load(home, projectRoot);

        assertEquals(1, snapshot.documents().size());
        MemoryDocument document = snapshot.documents().getFirst();
        assertEquals(perFileBudget, document.content().length());
        assertEquals(perFileBudget, snapshot.totalContentChars());
        assertTrue(document.truncated());
        assertTrue(snapshot.truncated());
        assertTrue(document.content().endsWith(LayeredMemoryLoader.TRUNCATION_MARKER));
        assertTrue(snapshot.renderPromptSection().contains(LayeredMemoryLoader.TRUNCATION_MARKER));
    }

    @Test
    void totalBudgetKeepsCloserMemoriesButReturnsThemBroadToSpecific() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("home"));
        Path projectRoot = Files.createDirectories(tempDir.resolve("project"));
        Files.createDirectories(projectRoot.resolve(".git"));
        Path module = Files.createDirectories(projectRoot.resolve("module"));

        write(home, "AGENTS.md", "global-001");
        Path project = write(projectRoot, "AGENTS.md", "project-01");
        Path nested = write(module, "AGENTS.md", "nested-001");

        MemorySnapshot snapshot = new LayeredMemoryLoader(100, 20).load(home, module);

        assertEquals(List.of(project, nested), paths(snapshot.documents()));
        assertEquals(List.of("project-01", "nested-001"),
                snapshot.documents().stream().map(MemoryDocument::content).toList());
        assertEquals(20, snapshot.totalContentChars());
        assertTrue(snapshot.truncated());
    }

    @Test
    void ignoresMissingAndEmptyMemoryFiles() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("home"));
        Path projectRoot = Files.createDirectories(tempDir.resolve("project"));
        Files.createDirectories(projectRoot.resolve(".git"));
        Path module = Files.createDirectories(projectRoot.resolve("module"));
        LayeredMemoryLoader loader = new LayeredMemoryLoader();

        MemorySnapshot missing = loader.load(home, module);

        assertTrue(missing.documents().isEmpty());
        assertEquals(0, missing.totalContentChars());
        assertFalse(missing.truncated());
        assertTrue(missing.renderPromptSection().isEmpty());

        write(home, "AGENTS.md", "");
        write(projectRoot, "CODEAGENT.md", "");
        write(module, "AGENTS.md", "");

        MemorySnapshot empty = loader.load(home, module);

        assertTrue(empty.documents().isEmpty());
        assertEquals(0, empty.totalContentChars());
        assertFalse(empty.truncated());
        assertTrue(empty.renderPromptSection().isEmpty());
    }

    @Test
    void resolvesRelativeAndNestedIncludesFromTheirSourceFiles() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("home"));
        Path projectRoot = Files.createDirectories(tempDir.resolve("project"));
        Files.createDirectories(projectRoot.resolve(".git"));
        write(projectRoot, "AGENTS.md", "before\n@docs/workflow.md\nafter");
        write(projectRoot, "docs/workflow.md", "workflow rules\n@details.md");
        write(projectRoot, "docs/details.md", "nested details");

        MemorySnapshot snapshot = new LayeredMemoryLoader().load(home, projectRoot);

        assertEquals(1, snapshot.documents().size());
        String content = snapshot.documents().getFirst().content();
        assertTrue(content.contains("before"), content);
        assertTrue(content.contains("<!-- included from docs/workflow.md -->"), content);
        assertTrue(content.contains("workflow rules"), content);
        assertTrue(content.contains("<!-- included from details.md -->"), content);
        assertTrue(content.contains("nested details"), content);
        assertTrue(content.contains("after"), content);
    }

    @Test
    void detectsRecursiveIncludeCycles() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("home"));
        Path projectRoot = Files.createDirectories(tempDir.resolve("project"));
        Files.createDirectories(projectRoot.resolve(".git"));
        write(projectRoot, "AGENTS.md", "@a.md");
        write(projectRoot, "a.md", "a rules\n@b.md");
        write(projectRoot, "b.md", "b rules\n@a.md");

        MemorySnapshot snapshot = new LayeredMemoryLoader().load(home, projectRoot);

        String content = snapshot.documents().getFirst().content();
        assertTrue(content.contains("a rules"), content);
        assertTrue(content.contains("b rules"), content);
        assertTrue(content.contains("<!-- include skipped: cycle detected a.md -->"), content);
    }

    @Test
    void rejectsParentTraversalAndAbsoluteIncludes() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("home"));
        Path projectRoot = Files.createDirectories(tempDir.resolve("project"));
        Files.createDirectories(projectRoot.resolve(".git"));
        Path outside = write(tempDir, "outside.md", "outside-secret-marker");
        write(projectRoot, "AGENTS.md", "@../outside.md\n@..\\outside.md\n@" + outside
                + "\n@C:\\outside.md\n@\\\\server\\share\\outside.md");

        MemorySnapshot snapshot = new LayeredMemoryLoader().load(home, projectRoot);

        String content = snapshot.documents().getFirst().content();
        assertTrue(content.contains("<!-- include skipped: unsafe path ../outside.md -->"), content);
        assertTrue(content.contains("<!-- include skipped: unsafe path ..\\outside.md -->"), content);
        assertTrue(content.contains("<!-- include skipped: unsafe path " + outside + " -->"), content);
        assertTrue(content.contains("<!-- include skipped: unsafe path C:\\outside.md -->"), content);
        assertTrue(content.contains("<!-- include skipped: unsafe path \\\\server\\share\\outside.md -->"), content);
        assertFalse(content.contains("outside-secret-marker"), content);
    }

    @Test
    void rejectsIncludeSymlinksThatEscapeTheMemoryBoundary() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("home"));
        Path projectRoot = Files.createDirectories(tempDir.resolve("project"));
        Files.createDirectories(projectRoot.resolve(".git"));
        Path docs = Files.createDirectories(projectRoot.resolve("docs"));
        Path outside = write(tempDir, "outside-secret.md", "symlink-secret-marker");
        createSymlinkOrSkip(docs.resolve("escape.md"), outside);
        write(projectRoot, "AGENTS.md", "@docs/escape.md");

        MemorySnapshot snapshot = new LayeredMemoryLoader().load(home, projectRoot);

        String content = snapshot.documents().getFirst().content();
        assertTrue(content.contains("<!-- include skipped: unsafe path docs/escape.md -->"), content);
        assertFalse(content.contains("symlink-secret-marker"), content);
    }

    @Test
    void skipsTopLevelMemorySymlinksThatEscapeTheirDiscoveryDirectory() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("home"));
        Path projectRoot = Files.createDirectories(tempDir.resolve("project"));
        Files.createDirectories(projectRoot.resolve(".git"));
        Path outside = write(tempDir, "outside-top-level.md", "top-level-secret-marker");
        createSymlinkOrSkip(projectRoot.resolve("AGENTS.md"), outside);

        MemorySnapshot snapshot = new LayeredMemoryLoader().load(home, projectRoot);

        assertTrue(snapshot.isEmpty());
        assertFalse(snapshot.renderPromptSection().contains("top-level-secret-marker"));
    }

    @Test
    void memoryReportDescribesExactlyTheBudgetedPromptFiles() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("home"));
        Path projectRoot = Files.createDirectories(tempDir.resolve("project"));
        Files.createDirectories(projectRoot.resolve(".git"));
        write(projectRoot, "AGENTS.md", "# Project memory\nUse project rules");

        MemorySnapshot snapshot = new LayeredMemoryLoader().load(home, projectRoot);
        String report = snapshot.renderReport(projectRoot);

        assertTrue(report.contains("Memory files loaded: 1"), report);
        assertTrue(report.contains("1. AGENTS.md"), report);
        assertTrue(report.contains("scope: project-root"), report);
        assertTrue(report.contains("lines: 2"), report);
        assertTrue(report.contains("preview: # Project memory"), report);
    }

    private static Path write(Path directory, String relativePath, String content) throws IOException {
        Path file = directory.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file.toAbsolutePath().normalize();
    }

    private static List<Path> paths(List<MemoryDocument> documents) {
        return documents.stream().map(MemoryDocument::path).toList();
    }

    private static void createSymlinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target.toAbsolutePath());
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "Symbolic links are unavailable: " + exception.getMessage());
        }
    }

    private static void assertAppearsBefore(String value, String first, String second) {
        int firstIndex = value.indexOf(first);
        int secondIndex = value.indexOf(second);
        assertTrue(firstIndex >= 0, () -> "Missing expected text: " + first);
        assertTrue(secondIndex >= 0, () -> "Missing expected text: " + second);
        assertTrue(firstIndex < secondIndex, () -> first + " should appear before " + second);
    }
}
