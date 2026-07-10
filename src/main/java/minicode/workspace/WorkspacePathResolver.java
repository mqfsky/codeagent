package minicode.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;

public final class WorkspacePathResolver {

    /**
     * 解析并规范化一次工具侧路径请求。
     *
     * <p>该方法会根据请求中的 {@code cwd}、原始路径、解析意图和路径策略，
     * 得到一个稳定的目标路径视图，包括规范化后的路径、真实路径、是否存在、
     * 文件类型判断以及是否仍位于工作区 {@code cwd} 边界内。</p>
     *
     * <p>工具层应通过该方法统一处理用户或模型传入的路径，避免各工具自行拼接、
     * 规范化或判断工作区边界。</p>
     *
     * @param request 路径解析请求，包含 cwd、原始路径、用途意图和路径策略
     * @return 路径解析结果，包含规范化路径、真实路径、存在性、类型和边界信息
     * @throws WorkspacePathException 当路径为空、非法、父目录不满足策略要求、
     *                                或路径策略校验失败时抛出
     */
    public WorkspacePathResult resolve(WorkspacePathRequest request) {
        Path cwd = normalizeCwd(request.cwd()); // 将工作路径转为绝对路径后规范化
        String rawPath = request.rawPath().trim(); // 取得模型传给工具的原始路径，也就是要操作的目录
        Path requested = Path.of(rawPath); // 转为 java 对象
        Path normalizedPath = requested.isAbsolute()
                ? requested.normalize() // 如果是绝对路径，则进行规范化
                : cwd.resolve(requested).normalize(); // 否则与 cwd 拼接

        // 目录是否存在
        boolean exists = Files.exists(normalizedPath, LinkOption.NOFOLLOW_LINKS);
        if (request.policy().mustExist() && !exists) {
            // 如果要读取不存在的文件会直接失败
            throw new WorkspacePathException("Path does not exist: " + rawPath);
        }

        // 取得软链接解析后的真实路径
        // 例如：
        // project/link.txt -> /private/data.txt
        // 那么：
        // normalizedPath = /project/link.txt
        // realPath       = /private/data.txt
        // 目的是防止软链接逃逸的关键。路径表面上可能在工作区里，真实目标却在工作区外。
        Optional<Path> realPath = readRealPath(normalizedPath, rawPath);
        // 当前工具要求文件，但传进来的却是目录，就报错. read_file(path="src")
        if (exists && !request.policy().allowDirectory() && Files.isDirectory(normalizedPath)) {
            throw new WorkspacePathException("Expected file but found directory: " + rawPath);
        }
        // 当前工具要求目录，但传进来的却是文件，也报错. list_files(path="pom.xml")
        if (exists && request.policy().allowDirectory() && !Files.isDirectory(normalizedPath)) {
            throw new WorkspacePathException("Expected directory but found file: " + rawPath);
        }

        // 取得工作区本身解析软链接后的真实路径，这样才能与目标路径比较
        Path cwdRealPath = cwdRealPath(cwd);

        // 获取父目录
        Optional<Path> parentRealPath = parentRealPathForMissingTarget(request, normalizedPath, exists);
        // 工作目录，软连接解析后的工作目录
        // 目标目录，软连接解析后的目标目录
        // 软连接解析后的目标目录父目录
        // 通过 startwith 比较目标路径是否在 cwd 内
        WorkspaceBoundary boundary = boundary(cwd, cwdRealPath, normalizedPath, realPath, parentRealPath);
        ResolvedWorkspacePath resolved = new ResolvedWorkspacePath(rawPath, normalizedPath, realPath, boundary);
        return new WorkspacePathResult(resolved, exists, parentRealPath);
    }

    private static Path normalizeCwd(Path cwd) {
        Path normalized = cwd.toAbsolutePath().normalize();
        if (!Files.exists(normalized)) {
            throw new WorkspacePathException("cwd does not exist: " + normalized);
        }
        if (!Files.isDirectory(normalized)) {
            throw new WorkspacePathException("cwd is not a directory: " + normalized);
        }
        return normalized;
    }

    private static Optional<Path> readRealPath(Path normalizedPath, String rawPath) {
        try {
            return Optional.of(normalizedPath.toRealPath());
        } catch (IOException exception) {
            if (Files.exists(normalizedPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new WorkspacePathException("Unable to resolve real path conservatively: " + rawPath, exception);
            }
            return Optional.empty();
        }
    }

    private static Path cwdRealPath(Path cwd) {
        try {
            return cwd.toRealPath();
        } catch (IOException exception) {
            throw new WorkspacePathException("Unable to resolve cwd real path: " + cwd, exception);
        }
    }

    /**
     * 新文件还不存在，没有 realPath，那就检查其父目录的 realPath，确保新文件最终不会通过软链接写到工作区外。
     * @param request
     * @param normalizedPath
     * @param exists
     * @return
     */
    private static Optional<Path> parentRealPathForMissingTarget(WorkspacePathRequest request, Path normalizedPath,
                                                                 boolean exists) {
        // 目标已存在，或者当前工具不是创建/写入目标的策略，不需要检查父目录，直接返回
        if (exists || request.policy() != WorkspacePathPolicy.TARGET_OR_EXISTING_PARENT) {
            return Optional.empty();
        }

        Path parent = normalizedPath.getParent();
        // 父目录不存在
        if (parent == null || !Files.exists(parent)) {
            throw new WorkspacePathException("Parent path does not exist: " + normalizedPath);
        }
        // 父路径不是目录
        if (!Files.isDirectory(parent)) {
            throw new WorkspacePathException("Parent path is not a directory: " + parent);
        }
        try {
            // 取得父目录跟随软链接后的真实路径
            return Optional.of(parent.toRealPath());
        } catch (IOException exception) {
            throw new WorkspacePathException("Unable to resolve parent real path conservatively: " + parent, exception);
        }
    }

    // 判断目标路径最终属于cwd 内还是外
    private static WorkspaceBoundary boundary(Path cwd, Path cwdRealPath, Path normalizedPath,
                                              Optional<Path> realPath, Optional<Path> parentRealPath) {
        if (!normalizedPath.startsWith(cwd)) {
            return WorkspaceBoundary.OUTSIDE_CWD;
        }
        if (realPath.isPresent() && !realPath.get().startsWith(cwdRealPath)) {
            return WorkspaceBoundary.OUTSIDE_CWD;
        }
        if (parentRealPath.isPresent() && !parentRealPath.get().startsWith(cwdRealPath)) {
            return WorkspaceBoundary.OUTSIDE_CWD;
        }
        return WorkspaceBoundary.INSIDE_CWD;
    }
}
