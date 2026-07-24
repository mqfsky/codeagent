package minicode.permissions;

import minicode.edit.EditReview;
import minicode.edit.UnifiedDiffBuilder;
import minicode.permissions.model.PermissionResource;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PermissionResourceTest {
    @Test
    void editResourceCarriesStructuredReviewPayload() {
        EditReview review = UnifiedDiffBuilder.build(
                Path.of("src/App.java"),
                PermissionResource.EditOperation.CREATE,
                "Create src/App.java",
                Optional.empty(),
                "class App {}\n"
        );
        PermissionResource.EditResource resource = new PermissionResource.EditResource(
                review,
                Optional.of("tool-use-1")
        );

        assertEquals(review, resource.review());
        assertEquals(Path.of("src/App.java"), resource.path());
        assertEquals(PermissionResource.EditOperation.CREATE, resource.operation());
        assertEquals("Create src/App.java", resource.summary());
        assertEquals("class App {}\n".length(), resource.afterChars());
        assertEquals(review.diffPreview(), resource.diffPreview());
        assertFalse(resource.originalExists());
        assertTrue(resource.reviewFingerprint().length() >= 32);
        assertTrue(resource.diffRef().orElseThrow().startsWith("sha256:"));
        assertEquals(Optional.of("tool-use-1"), resource.toolUseId());
    }

    @Test
    void externalActionResourceCarriesAnImmutableExplicitReviewPayload() {
        List<String> facts = new ArrayList<>(List.of("Document type: docx"));
        PermissionResource.ExternalActionResource resource = new PermissionResource.ExternalActionResource(
                "feishu",
                "append_blocks",
                "doc-123",
                "sha256:external-action",
                facts
        );

        facts.add("Added after construction");

        assertEquals("feishu", resource.service());
        assertEquals("append_blocks", resource.action());
        assertEquals("doc-123", resource.target());
        assertEquals("sha256:external-action", resource.fingerprint());
        assertEquals(List.of("Document type: docx"), resource.facts());
        assertThrows(UnsupportedOperationException.class,
                () -> resource.facts().add("Cannot mutate"));
        assertThrows(IllegalArgumentException.class,
                () -> new PermissionResource.ExternalActionResource(
                        "feishu", "append_blocks", "doc-123", " ", List.of()));
    }
}
