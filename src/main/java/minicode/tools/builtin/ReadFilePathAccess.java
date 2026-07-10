package minicode.tools.builtin;

import minicode.permissions.model.PathIntent;
import minicode.permissions.model.PermissionContext;
import minicode.permissions.api.PermissionPromptHandler;
import minicode.permissions.api.PermissionService;
import minicode.permissions.service.PromptingPermissionService;
import minicode.tools.api.ToolContext;
import minicode.workspace.ResolvedWorkspacePath;
import minicode.workspace.WorkspaceBoundary;

import java.nio.file.Path;
import java.util.Objects;

@FunctionalInterface
public interface ReadFilePathAccess {
    void ensureReadAllowed(ToolContext toolContext, ResolvedWorkspacePath resolvedPath);

    static ReadFilePathAccess unavailable() {
        return fromPermissionService(new PromptingPermissionService(PermissionPromptHandler.unavailable()));
    }

    static ReadFilePathAccess fromPermissionService(PermissionService permissionService) {
        PermissionService actualPermissionService = Objects.requireNonNull(permissionService, "permissionService");
        // 真正的权限判断
        return (toolContext, resolvedPath) -> {
            // cwd 内，直接读
            if (resolvedPath.boundary() == WorkspaceBoundary.INSIDE_CWD) {
                return;
            }
            // cwd 外，查看权限，或者提给用户判断
            actualPermissionService.ensurePath(
                    resolvedPath.normalizedPath(),
                    PathIntent.READ,
                    new PermissionContext(toolContext.sessionId(), toolContext.turnId(), toolContext.toolUseId())
            );
        };
    }
}
