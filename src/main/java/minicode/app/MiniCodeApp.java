package minicode.app;

import minicode.config.RuntimeConfig;
import minicode.config.RuntimeConfigException;
import minicode.config.RuntimeConfigLoader;
import minicode.core.event.AgentEventSink;
import minicode.permissions.api.PermissionPromptHandler;
import minicode.session.service.SessionService;
import minicode.session.store.SessionMetadata;
import minicode.session.store.SessionStore;
import minicode.tui.ConsolePermissionPromptHandler;
import minicode.tui.MiniTui;
import minicode.tui.MiniTuiEventSink;
import minicode.tui.RendererTuiBridge;
import minicode.tui.RendererTuiShell;
import minicode.tui.input.JLineTuiInput;
import minicode.tui.terminal.JLineTerminalScreen;
import minicode.tui.terminal.TerminalScreen;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.UUID;
import java.util.Arrays;
import java.util.List;

/**
 * CodeAgent 版本的命令行入口。
 * java -jar target/codeagent.jar
 * <p>这个类负责完成进程启动阶段的工作：解析命令行参数、加载运行配置、
 * 初始化会话与 TUI 输入输出环境，并把真正的交互循环交给 {@link MiniTui}
 * 或 {@link RendererTuiShell}。</p>
 */
public final class MiniCodeApp {
    private static final String VERSION = MiniCodeApp.class.getPackage().getImplementationVersion() == null
            ? "0.1.0-SNAPSHOT"
            : MiniCodeApp.class.getPackage().getImplementationVersion();

    /**
     * 工具类构造器。
     *
     * <p>应用入口只暴露静态方法，不需要被实例化。</p>
     */
    private MiniCodeApp() {
    }

    /**
     * Java 进程入口，接收启动参数并把控制权交给 {@link #run(String[], Path, Path, InputStream, OutputStream, OutputStream, Map)}。
     *
     * @param args 启动命令传入的参数，例如 {@code --cwd}、{@code --resume}、{@code --max-steps}
     */
    public static void main(String[] args) {
        // 将真实进程环境包装成可测试的 run(...) 入参。
        int exitCode = run(
                args,
                Path.of(System.getProperty("user.home"), ".codeagent"),
                Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize(),
                System.in,
                System.out,
                System.err,
                System.getenv()
        );
        // 非 0 退出码代表启动或运行失败，需要显式结束进程。
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /**
     * 使用默认 SnakeGame 启动器运行 CodeAgent。
     *
     * @param args 启动参数
     * @param home CodeAgent 的数据目录，通常是 {@code ~/.codeagent}
     * @param cwd 默认工作目录，通常是启动进程时的当前目录
     * @param input 用户输入流，通常是 {@code System.in}
     * @param output 标准输出流，通常是 {@code System.out}
     * @param error 标准错误流，通常是 {@code System.err}
     * @param env 环境变量，用于加载 provider、model 和鉴权配置
     * @return 进程退出码，{@code 0} 表示正常结束，非 {@code 0} 表示启动或运行失败
     */
    public static int run(String[] args, Path home, Path cwd, InputStream input, OutputStream output,
                          OutputStream error, Map<String, String> env) {
        // 普通启动时使用真实的 SnakeGame 启动逻辑；测试可以调用下面的重载替换它。
        return run(args, home, cwd, input, output, error, env, MiniCodeApp::launchSnakeGame);
    }

    /**
     * CodeAgent 的主启动流程。
     *
     * <p>这个方法先解析命令行参数，处理 {@code --snake}、{@code --help}、
     * {@code --version} 和 session 管理命令；普通交互模式下会加载运行配置，
     * 然后创建应用服务并进入 TUI 循环。</p>
     *
     * @param args 启动参数
     * @param home CodeAgent 的数据目录
     * @param cwd 默认工作目录
     * @param input 用户输入流
     * @param output 标准输出流
     * @param error 标准错误流
     * @param env 环境变量
     * @param snakeLauncher SnakeGame 彩蛋的启动回调，测试时可以替换为假的实现
     * @return 进程退出码，{@code 0} 表示正常结束，{@code 1} 表示运行错误，{@code 2} 表示配置错误
     */
    public static int run(String[] args, Path home, Path cwd, InputStream input, OutputStream output,
                          OutputStream error, Map<String, String> env, Runnable snakeLauncher) {
        // 错误输出统一走 UTF-8 PrintWriter，后续异常会先做敏感信息脱敏再打印。
        PrintWriter err = new PrintWriter(error, true, StandardCharsets.UTF_8);
        AppArgs appArgs;
        Path actualCwd;
        try {
            // 第一步：解析启动参数，并计算真正的 workspace cwd。
            appArgs = AppArgs.parse(args);
            actualCwd = resolveActualCwd(cwd, appArgs);
        } catch (RuntimeException exception) {
            // 参数解析或 cwd 校验失败时，程序还没进入服务创建阶段，直接返回启动失败。
            err.println("Runtime error: " + safeMessage(exception, env));
            return 1;
        }

        // 处理特殊命令：--snake 是彩蛋分支，不加载模型配置，也不启动 CodeAgent TUI。
        if (appArgs.snake()) {
            PrintWriter out = new PrintWriter(output, true, StandardCharsets.UTF_8);
            out.println("Starting SnakeGame...");
            try {
                // snakeLauncher 被抽成参数，方便测试时替换为无副作用实现。
                snakeLauncher.run();
                return 0;
            } catch (RuntimeException exception) {
                // 彩蛋启动失败也要走 safeMessage，避免错误信息里带出敏感环境变量。
                err.println("Runtime error: " + safeMessage(exception, env));
                return 1;
            }
        }

        // 处理 --help：只打印帮助文本，不创建服务。
        if (appArgs.help()) {
            new PrintWriter(output, true, StandardCharsets.UTF_8).println(usage());
            return 0;
        }

        // 处理 --version：只打印版本号，不创建服务。
        if (appArgs.version()) {
            new PrintWriter(output, true, StandardCharsets.UTF_8).println("codeagent " + VERSION);
            return 0;
        }

        // 处理 session list / session rename：只需要 SessionStore，不应该启动完整应用服务。
        if (appArgs.sessionCommand()) {
            return runWithServices(args, home, cwd, input, output, error,
                    (serviceHome, serviceCwd, sessionId, eventSink, permissionPromptHandler) -> {
                        throw new IllegalStateException("Session management command must not start application services.");
                    },
                    env);
        }

        // 普通交互模式必须先加载运行配置，例如 provider、model、token、MCP 等。
        RuntimeConfig runtimeConfig;
        try {
            runtimeConfig = RuntimeConfigLoader.load(new RuntimeConfigLoader.Input(home, actualCwd, env));
        } catch (RuntimeConfigException exception) {
            // 配置错误单独返回 2，方便调用方区分“配置缺失”和“运行异常”。
            err.println("Configuration error: " + exception.getMessage());
            err.println("Configure CODEAGENT_PROVIDER, ANTHROPIC_MODEL or CODEAGENT_MODEL, and ANTHROPIC_AUTH_TOKEN or ANTHROPIC_API_KEY.");
            err.println("Mock mode is only used when CODEAGENT_PROVIDER=mock is explicitly set.");
            return 2;
        }

        // 将 RuntimeConfig 闭包进 servicesFactory；真正的服务创建发生在 runWithServicesUnchecked(...)。
        return runWithServices(args, home, cwd, input, output, error,
                (serviceHome, serviceCwd, sessionId, eventSink, permissionPromptHandler) -> ApplicationServices.create(
                        serviceHome,
                        serviceCwd,
                        sessionId,
                        runtimeConfig,
                        eventSink,
                        permissionPromptHandler
                ),
                env);
    }

    /**
     * 使用调用方提供的服务工厂运行 CodeAgent。
     *
     * <p>这个重载主要服务测试场景：测试可以注入自定义 {@link ServicesFactory}，
     * 避免真的创建网络模型、权限存储或终端 UI 依赖。</p>
     *
     * @param args 启动参数
     * @param home CodeAgent 的数据目录
     * @param cwd 默认工作目录
     * @param input 用户输入流
     * @param output 标准输出流
     * @param error 标准错误流
     * @param servicesFactory 应用服务创建工厂
     * @return 进程退出码，{@code 0} 表示正常结束，非 {@code 0} 表示失败
     */
    public static int runWithServices(String[] args, Path home, Path cwd, InputStream input, OutputStream output,
                                      OutputStream error, ServicesFactory servicesFactory) {
        // 没有传 env 的测试入口不做 token 脱敏匹配，使用空环境即可。
        return runWithServices(args, home, cwd, input, output, error, servicesFactory, Map.of());
    }

    /**
     * 带异常保护的服务启动入口。
     *
     * <p>真实启动逻辑在 {@link #runWithServicesUnchecked(String[], Path, Path, InputStream, OutputStream, OutputStream, ServicesFactory)}
     * 中执行；本方法负责捕获运行时异常，并通过 {@link #safeMessage(RuntimeException, Map)} 对错误信息做脱敏。</p>
     *
     * @param args 启动参数
     * @param home CodeAgent 的数据目录
     * @param cwd 默认工作目录
     * @param input 用户输入流
     * @param output 标准输出流
     * @param error 标准错误流
     * @param servicesFactory 应用服务创建工厂
     * @param env 环境变量，用于错误信息脱敏
     * @return 进程退出码，{@code 0} 表示正常结束，{@code 1} 表示运行时异常
     */
    private static int runWithServices(String[] args, Path home, Path cwd, InputStream input, OutputStream output,
                                       OutputStream error, ServicesFactory servicesFactory, Map<String, String> env) {
        // 这一层只负责把 unchecked 启动流程包进统一异常处理。
        PrintWriter err = new PrintWriter(error, true, StandardCharsets.UTF_8);
        try {
            runWithServicesUnchecked(args, home, cwd, input, output, error, servicesFactory);
            return 0;
        } catch (RuntimeException exception) {
            // 任意运行时异常都转成用户可读错误和退出码 1。
            err.println("Runtime error: " + safeMessage(exception, env));
            return 1;
        }
    }

    /**
     * 创建会话、服务对象和 TUI，并启动交互循环。
     *
     * <p>这个方法是启动装配的核心：它解析 session 参数，处理 resume/fork，
     * 创建输入读取器和 JLine 终端，然后根据终端能力选择普通 line mode
     * 或 Renderer TUI。方法名里的 unchecked 表示异常由外层统一捕获。</p>
     *
     * @param args 启动参数
     * @param home CodeAgent 的数据目录
     * @param cwd 默认工作目录
     * @param input 用户输入流
     * @param output 标准输出流
     * @param error 标准错误流
     * @param servicesFactory 应用服务创建工厂
     */
    private static void runWithServicesUnchecked(String[] args, Path home, Path cwd, InputStream input,
                                                 OutputStream output, OutputStream error, ServicesFactory servicesFactory) {
        // out 用于正常命令输出，err 用于诊断和 fallback 提示。
        PrintWriter out = new PrintWriter(output, true, StandardCharsets.UTF_8);
        PrintWriter err = new PrintWriter(error, true, StandardCharsets.UTF_8);

        // 重新解析参数，确保 session 子命令、resume/fork 和 TUI 启动共享同一套解析逻辑。
        AppArgs appArgs = AppArgs.parse(args);
        Path actualCwd = resolveActualCwd(cwd, appArgs);
        Path actualHome = home.toAbsolutePath().normalize();

        // SessionStore 位于 home/sessions；session 相关命令和交互模式都会用到它。
        SessionService sessionService = new SessionService(new SessionStore(actualHome.resolve("sessions")));

        // session 管理命令到这里就结束，不会继续创建模型、工具和 TUI。
        if (appArgs.sessionCommand()) {
            handleSessionCommand(appArgs, sessionService, actualCwd.toString(), out);
            return;
        }

        // 默认新建 UUID session；如果位置参数提供 sessionId，则复用该 id。
        String sessionId = appArgs.sessionId().orElseGet(() -> UUID.randomUUID().toString());

        // --resume 要求目标 session 在当前 cwd 下存在，校验通过后沿用原 sessionId。
        if (appArgs.resumeSessionId() != null) {
            sessionService.requireResumable(actualCwd.toString(), appArgs.resumeSessionId());
            sessionId = appArgs.resumeSessionId();
        }

        // --fork 会复制源 session 的历史，并生成一个新的 sessionId。
        if (appArgs.forkSessionId() != null) {
            sessionId = sessionService.fork(actualCwd.toString(), appArgs.forkSessionId());
            out.println("Forked session " + appArgs.forkSessionId() + " -> " + sessionId);
        }

        // reader 是普通 line mode 和控制台权限提示的输入来源。
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));

        // 尝试创建 JLine terminal；失败时 terminal 为 null，后续退回 MiniTui。
        Terminal terminal = createTerminal(input, output, err);

        // RendererTuiBridge 同时承担事件展示和权限弹窗；普通模式则使用 ConsolePermissionPromptHandler。
        RendererTuiBridge rendererBridge = terminal == null ? null : new RendererTuiBridge();
        PermissionPromptHandler permissionPromptHandler = rendererBridge == null
                ? new ConsolePermissionPromptHandler(reader, output)
                : rendererBridge;

        // 创建应用服务集合：工具注册、模型适配器、权限服务、上下文管理、session runner 都在里面装配。
        ApplicationServices services = servicesFactory.create(
                actualHome,
                actualCwd,
                sessionId,
                rendererBridge == null ? new MiniTuiEventSink(output, event -> {
                }) : rendererBridge,
                permissionPromptHandler
        );

        // 只有 Renderer TUI 需要 TerminalScreen 来做全屏式渲染。
        TerminalScreen terminalScreen = terminal == null ? null : new JLineTerminalScreen(terminal);
        try {
            // 没有 JLine 终端，走普通 line mode 输入链路：readLine -> UserMessage -> AgentLoop。
            if (terminal == null) {
                new MiniTui(services, reader, output,
                        effectiveMaxSteps(java.util.Optional.ofNullable(appArgs.maxStepsOverride()),
                                services.runtimeConfig())).runLoop();
            } else {
                // 有 JLine 终端，走 Renderer TUI：支持状态刷新、权限选择和后台 turn 执行。
                new RendererTuiShell(services, new JLineTuiInput(terminal), terminalScreen,
                        effectiveMaxSteps(java.util.Optional.ofNullable(appArgs.maxStepsOverride()),
                                services.runtimeConfig()), rendererBridge).runLoop();
            }
        } finally {
            // TUI 退出后释放屏幕资源、MCP runtime 等应用服务资源。
            if (terminalScreen != null) {
                terminalScreen.close();
            }
            services.close();
            if (terminal != null) {
                try {
                    terminal.close();
                } catch (java.io.IOException ignored) {
                    // Closing the terminal is best-effort during app shutdown.
                }
            }
        }
    }

    /**
     * 尝试创建 JLine 终端对象。
     *
     * <p>如果当前环境支持完整终端能力，返回可用于 Renderer TUI 的 {@link Terminal}；
     * 如果终端不可用或被识别为 {@code dumb}，返回 {@code null}，调用方会退回普通 line mode。</p>
     *
     * @param input 用户输入流
     * @param output 标准输出流
     * @param err 错误输出 writer，用于打印 fallback 诊断信息
     * @return 可用的 JLine {@link Terminal}，或 {@code null} 表示需要退回普通输入模式
     */
    private static Terminal createTerminal(InputStream input, OutputStream output, PrintWriter err) {
        try {
            // JLine 会基于当前系统终端能力创建可交互 Terminal。
            Terminal terminal = TerminalBuilder.builder()
                    .system(true)
                    .streams(input, output)
                    .encoding(StandardCharsets.UTF_8)
                    .build();

            // dumb terminal 不支持复杂光标控制，不能用于 Renderer TUI。
            return terminal.getType() == null || "dumb".equalsIgnoreCase(terminal.getType()) ? null : terminal;
        } catch (RuntimeException | java.io.IOException exception) {
            // 终端初始化失败不是致命错误，退回普通 line mode 仍可使用。
            err.println("TUI fallback: JLine terminal unavailable, using line mode. " + exception.getMessage());
            return null;
        }
    }

    /**
     * 计算本次运行实际使用的工作目录。
     *
     * @param cwd 默认工作目录，通常是启动进程时的当前目录
     * @param appArgs 已解析的命令行参数，可能包含 {@code --cwd}
     * @return 规范化后的实际工作目录
     * @throws IllegalArgumentException 当 {@code --cwd} 指向不存在或非目录路径时抛出
     */
    private static Path resolveActualCwd(Path cwd, AppArgs appArgs) {
        // 用户传了 --cwd 就优先使用它，否则使用进程启动时的当前目录。
        Path actualCwd = appArgs.cwdOverride() != null
                ? appArgs.cwdOverride().toAbsolutePath().normalize()
                : cwd.toAbsolutePath().normalize();

        // 显式传入的 --cwd 必须存在且是目录，避免后续工具在无效 workspace 中执行。
        if (appArgs.cwdOverride() != null && (!Files.exists(actualCwd) || !Files.isDirectory(actualCwd))) {
            throw new IllegalArgumentException("--cwd must be an existing directory: " + actualCwd);
        }

        // 返回规范化路径，后续 session 隔离、工具路径解析都会使用它。
        return actualCwd;
    }

    /**
     * 处理 session 管理子命令。
     *
     * <p>当前支持 {@code session list} 和 {@code session rename <id> <title>}。
     * 这些命令只操作会话元数据，不会启动模型或 TUI。</p>
     *
     * @param args 已解析的应用参数，剩余参数中包含 session 子命令
     * @param sessionService 会话服务，用于列出和重命名 session
     * @param cwd 当前工作目录，session 会按 cwd 隔离
     * @param out 标准输出 writer
     */
    private static void handleSessionCommand(AppArgs args, SessionService sessionService, String cwd, PrintWriter out) {
        // session 子命令保留在 remaining 中，例如 ["session", "list"]。
        List<String> command = args.remaining();
        String subcommand = command.size() > 1 ? command.get(1) : "";
        switch (subcommand) {
            case "list" -> {
                // 按 cwd 列出 session；CodeAgent 的 session 是 workspace 隔离的。
                List<SessionMetadata> sessions = sessionService.list(cwd);
                if (sessions.isEmpty()) {
                    out.println("No sessions for cwd: " + cwd);
                    return;
                }
                // 有 session 时打印表头和每一行元数据。
                out.println(formatSessionListHeader());
                sessions.forEach(session -> out.println(formatSessionListRow(session)));
            }
            case "rename" -> {
                // rename 需要 session id 和新标题，标题可以包含空格。
                if (command.size() < 4) {
                    throw new IllegalArgumentException("Usage: session rename <id> <title>");
                }
                String sessionId = command.get(2);
                String title = String.join(" ", command.subList(3, command.size()));
                // 重命名也是追加一条 session meta 事件，而不是改写历史消息。
                sessionService.rename(cwd, sessionId, title);
                out.println("Renamed session " + sessionId + " to " + title.trim());
            }
            // 未知子命令直接提示合法用法。
            default -> throw new IllegalArgumentException("Usage: session list | session rename <id> <title>");
        }
    }

    /**
     * 生成命令行帮助文本。
     *
     * @return 可直接打印到终端的 usage/help 文本
     */
    private static String usage() {
        return """
                Usage:
                  codeagent
                  codeagent --cwd <path>
                  codeagent --resume <id>
                  codeagent --fork <id>
                  codeagent session list
                  codeagent session rename <id> <title>
                  codeagent --max-steps <n>
                  codeagent --version
                  codeagent --help

                Options:
                  --cwd <path>       Use an explicit workspace directory.
                  --resume <id>      Resume a session for the current workspace.
                  --fork <id>        Fork a session for the current workspace.
                  --max-steps <n>    Limit one agent turn to 1..100 model/tool steps.
                  --version          Print version and exit.
                  --help             Print this help and exit.
                """;
    }

    /**
     * 生成 session 列表的表头。
     *
     * @return 固定宽度的 session 列表表头字符串
     */
    private static String formatSessionListHeader() {
        // 使用固定列宽，保证 session list 在终端中对齐。
        return String.format(java.util.Locale.ROOT, "%-36s  %-40s  %-30s  %s",
                "SESSION ID", "TITLE", "UPDATED", "CWD");
    }

    /**
     * 生成 session 列表中的单行展示文本。
     *
     * @param session 会话元数据
     * @return 固定宽度的 session 展示行
     */
    private static String formatSessionListRow(SessionMetadata session) {
        // session id、标题和更新时间都可能很长，展示前统一裁剪。
        return String.format(java.util.Locale.ROOT, "%-36s  %-40s  %-30s  %s",
                truncate(session.sessionId(), 36),
                truncate(session.title().orElse("(untitled)"), 40),
                truncate(session.updatedAt().toString(), 30),
                session.cwd());
    }

    /**
     * 将过长字符串裁剪到指定长度。
     *
     * @param value 原始字符串
     * @param maxChars 最大展示字符数
     * @return 未超长时返回原字符串，超长时返回带 {@code ...} 的裁剪结果
     */
    private static String truncate(String value, int maxChars) {
        // 未超过最大长度时保持原样，避免无意义地增加省略号。
        if (value.length() <= maxChars) {
            return value;
        }
        // 极小宽度下没有空间放完整省略号，直接硬截断。
        if (maxChars <= 3) {
            return value.substring(0, maxChars);
        }
        // 常规裁剪保留 maxChars 总长度，其中末尾 3 个字符用于 "...".
        return value.substring(0, maxChars - 3) + "...";
    }

    /**
     * 生成适合打印给用户的安全错误信息。
     *
     * <p>该方法会从异常中提取 message，并把环境变量里的敏感 token 替换为
     * {@code <redacted>}，避免启动失败时把密钥打印到终端。</p>
     *
     * @param exception 需要展示的运行时异常
     * @param env 环境变量，用于查找需要脱敏的 token 值
     * @return 脱敏后的错误信息
     */
    private static String safeMessage(RuntimeException exception, Map<String, String> env) {
        // 优先使用异常 message；没有 message 时退回异常类型名。
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }

        // 防止 provider token 出现在异常信息中被打印到终端。
        for (String key : java.util.List.of("ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_API_KEY")) {
            String value = env.get(key);
            if (value != null && !value.isBlank()) {
                message = message.replace(value, "<redacted>");
            }
        }
        return message;
    }

    /**
     * 计算一次 Agent turn 的最大 step 数。
     *
     * <p>优先级为：命令行 {@code --max-steps} &gt; 运行配置中的 maxSteps
     * &gt; {@link MiniTui#DEFAULT_MAX_STEPS}。</p>
     *
     * @param cliMaxSteps 命令行传入的最大 step 数
     * @param runtimeConfig 运行配置，可能包含默认 maxSteps
     * @return 本次运行实际使用的最大 step 数
     */
    static int effectiveMaxSteps(java.util.Optional<Integer> cliMaxSteps,
                                 java.util.Optional<RuntimeConfig> runtimeConfig) {
        // 优先使用命令行覆盖值，其次使用配置文件值，最后使用内置默认值。
        return cliMaxSteps
                .or(() -> runtimeConfig.flatMap(RuntimeConfig::maxSteps))
                .orElse(MiniTui.DEFAULT_MAX_STEPS);
    }

    /**
     * 启动 SnakeGame 彩蛋。
     *
     * <p>该方法定位 SnakeGame jar，并用当前 Java 运行时另起进程启动它。</p>
     *
     * @throws IllegalStateException 当 jar 找不到、进程启动失败或游戏进程异常退出时抛出
     */
    private static void launchSnakeGame() {
        // 先定位 snake.jar，再使用当前 JRE 的 java/java.exe 启动它。
        Path jar = snakeJarPath();
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java").toString();

        // inheritIO 让 SnakeGame 直接复用当前终端输入输出。
        ProcessBuilder processBuilder = new ProcessBuilder(javaExecutable, "-jar", jar.toString());
        processBuilder.inheritIO();
        try {
            // 等待游戏进程结束，并把非 0 退出码视为启动失败。
            Process process = processBuilder.start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("SnakeGame exited with code " + exitCode);
            }
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Failed to start SnakeGame: " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            // 如果等待进程时被中断，恢复中断标记再抛出业务异常。
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for SnakeGame.", exception);
        }
    }

    /**
     * 查找 SnakeGame jar 的实际路径。
     *
     * <p>查找顺序包括系统属性覆盖值、当前代码源附近路径、源码目录路径和打包分发目录路径。</p>
     *
     * @return 存在的 SnakeGame jar 路径
     * @throws IllegalStateException 当所有候选路径都不存在时抛出
     */
    private static Path snakeJarPath() {
        // 收集所有可能的 snake.jar 位置，按优先级顺序尝试。
        java.util.ArrayList<Path> candidates = new java.util.ArrayList<>();

        // 系统属性允许测试或用户显式覆盖 snake.jar 路径。
        String override = System.getProperty("codeagent.snake.jar");
        if (override != null && !override.isBlank()) {
            candidates.add(Path.of(override));
        }

        // 根据当前 class 或 jar 的位置推断分发包中的 easter-eggs 目录。
        codeSourcePath().ifPresent(codePath -> {
            Path parent = Files.isRegularFile(codePath) ? codePath.getParent() : codePath;
            if (parent != null) {
                candidates.add(parent.resolve("easter-eggs").resolve("snake").resolve("snake.jar"));
                candidates.add(parent.resolve("..").resolve("easter-eggs").resolve("snake").resolve("snake.jar"));
                candidates.add(parent.resolve("..").resolve("..").resolve("easter-eggs").resolve("snake").resolve("snake.jar"));
                candidates.add(parent.resolve("dist").resolve("codeagent").resolve("easter-eggs").resolve("snake").resolve("snake.jar"));
            }
        });
        candidates.add(Path.of("easter-eggs", "snake", "snake.jar"));
        candidates.add(Path.of("target", "dist", "codeagent", "easter-eggs", "snake", "snake.jar"));

        // 返回第一个真实存在的候选路径。
        for (Path candidate : candidates) {
            Path actual = candidate.toAbsolutePath().normalize();
            if (Files.isRegularFile(actual)) {
                return actual;
            }
        }

        // 没有找到 jar 时让 --snake 分支报出明确错误。
        throw new IllegalStateException("SnakeGame jar not found. Expected easter-eggs/snake/snake.jar near CodeAgent.");
    }

    /**
     * 获取当前 {@link MiniCodeApp} 类的代码来源路径。
     *
     * <p>当程序从 jar 运行时，这通常是 jar 文件路径；在 IDE 或测试环境中，
     * 可能是 class 输出目录。获取失败时返回空。</p>
     *
     * @return 当前类加载来源路径，获取失败时为 {@link java.util.Optional#empty()}
     */
    private static java.util.Optional<Path> codeSourcePath() {
        try {
            // ProtectionDomain 可以告诉我们当前类文件或 jar 是从哪里加载的。
            return java.util.Optional.of(Path.of(MiniCodeApp.class.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI()).toAbsolutePath().normalize());
        } catch (URISyntaxException | RuntimeException exception) {
            // 在受限运行环境中可能拿不到 code source，调用方会用其它候选路径兜底。
            return java.util.Optional.empty();
        }
    }

    /**
     * 判断当前操作系统是否为 Windows。
     *
     * @return 如果 {@code os.name} 包含 {@code windows} 则返回 {@code true}，否则返回 {@code false}
     */
    private static boolean isWindows() {
        // Windows 下 Java 可执行文件通常叫 java.exe，其它平台通常叫 java。
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    /**
     * 应用服务创建工厂。
     *
     * <p>生产环境用它创建真实的 {@link ApplicationServices}；测试可以注入自定义实现，
     * 以便隔离模型、权限、文件系统或终端依赖。</p>
     */
    @FunctionalInterface
    public interface ServicesFactory {
        /**
         * 创建本次运行使用的应用服务集合。
         *
         * @param home CodeAgent 的数据目录
         * @param cwd 实际工作目录
         * @param sessionId 当前 session 标识
         * @param eventSink Agent 事件出口，用于把工具、状态、上下文统计等事件发送给 UI
         * @param permissionPromptHandler 权限提示处理器，用于向用户请求路径、命令或编辑授权
         * @return 初始化完成的 {@link ApplicationServices}
         */
        ApplicationServices create(Path home, Path cwd, String sessionId, AgentEventSink eventSink,
                                   PermissionPromptHandler permissionPromptHandler);
    }

    /**
     * 命令行参数解析后的结构化结果。
     *
     * @param resumeSessionId {@code --resume} 指定的会话 id
     * @param forkSessionId {@code --fork} 指定的源会话 id
     * @param cwdOverride {@code --cwd} 指定的工作目录覆盖值
     * @param help 是否请求帮助信息
     * @param version 是否请求版本信息
     * @param snake 是否请求启动 SnakeGame 彩蛋
     * @param maxStepsOverride {@code --max-steps} 指定的一轮最大 step 数
     * @param remaining 未被通用参数解析消费的剩余参数，例如 {@code session list}
     */
    private record AppArgs(String resumeSessionId, String forkSessionId, Path cwdOverride,
                           boolean help, boolean version, boolean snake,
                           Integer maxStepsOverride, List<String> remaining) {
        private static final int DEFAULT_MAX_STEPS = MiniTui.DEFAULT_MAX_STEPS;
        private static final int MAX_MAX_STEPS = 100;

        /**
         * 解析原始命令行参数。
         *
         * @param args {@code main(String[] args)} 接收到的原始参数
         * @return 结构化后的应用参数
         * @throws IllegalArgumentException 当参数缺值、非法或 {@code --resume}/{@code --fork} 同时出现时抛出
         */
        private static AppArgs parse(String[] args) {
            // 使用可变列表逐步消费已识别参数，剩下的就是位置参数或子命令。
            List<String> remaining = new java.util.ArrayList<>(Arrays.asList(args));

            // 先取不带值的开关参数。
            boolean help = takeFlag(remaining, "--help") || takeFlag(remaining, "-h");
            boolean version = takeFlag(remaining, "--version");
            boolean snake = takeFlag(remaining, "--snake");

            // 再取带值的选项参数。
            String cwd = takeOption(remaining, "--cwd");
            String maxSteps = takeOption(remaining, "--max-steps");
            String resume = takeOption(remaining, "--resume");
            String fork = takeOption(remaining, "--fork");

            // resume 和 fork 都代表基于已有 session 启动，语义互斥。
            if (resume != null && fork != null) {
                throw new IllegalArgumentException("Use either --resume or --fork, not both.");
            }

            // 把解析后的字段收拢到 AppArgs；remaining 只保留未消费参数。
            return new AppArgs(resume, fork, cwd == null ? null : Path.of(cwd), help, version, snake,
                    maxSteps == null ? null : parseMaxSteps(maxSteps),
                    List.copyOf(remaining));
        }

        /**
         * 判断剩余参数是否表示 session 管理命令。
         *
         * @return 如果剩余参数以 {@code session} 开头则返回 {@code true}
         */
        private boolean sessionCommand() {
            // session 命令以位置参数形式出现，例如 codeagent session list。
            return !remaining.isEmpty() && "session".equals(remaining.getFirst());
        }

        /**
         * 从剩余参数中解析位置参数形式的 session id。
         *
         * <p>这不同于 {@code --resume}：它支持 {@code codeagent <sessionId>} 这种位置参数形式。
         * 如果剩余第一个参数以 {@code -} 开头，则认为是未知参数并抛出异常。</p>
         *
         * @return 位置参数 session id；没有位置参数时返回空
         * @throws IllegalArgumentException 当剩余第一个参数看起来像未知 flag 时抛出
         */
        private java.util.Optional<String> sessionId() {
            // 没有剩余位置参数时，调用方会生成新的 UUID sessionId。
            if (remaining.isEmpty()) {
                return java.util.Optional.empty();
            }

            // 剩余第一个参数如果像 flag，说明它不是合法 session id，而是未知参数。
            String first = remaining.getFirst();
            if (first.startsWith("-")) {
                throw new IllegalArgumentException("Unknown argument: " + first);
            }

            // 剩余第一个普通参数被解释为位置参数形式的 session id。
            return java.util.Optional.of(first);
        }

        /**
         * 从参数列表中取出带值选项。
         *
         * <p>例如 {@code --cwd /tmp/project} 会返回 {@code /tmp/project}，
         * 并把 {@code --cwd} 及其值从列表中移除。</p>
         *
         * @param args 可变参数列表
         * @param name 选项名，例如 {@code --cwd}
         * @return 选项值；不存在该选项时返回 {@code null}
         * @throws IllegalArgumentException 当选项存在但缺少值时抛出
         */
        private static String takeOption(List<String> args, String name) {
            // 在当前参数列表中寻找选项名。
            int index = args.indexOf(name);
            if (index < 0) {
                return null;
            }

            // 带值选项必须紧跟一个值。
            if (index + 1 >= args.size()) {
                throw new IllegalArgumentException("Missing value for " + name);
            }

            // 取出值后，把 name 和 value 都移除，避免后面被当成 remaining。
            String value = args.get(index + 1);
            args.remove(index + 1);
            args.remove(index);
            return value;
        }

        /**
         * 从参数列表中取出布尔开关。
         *
         * <p>例如 {@code --help}、{@code --version} 这类不带值的参数。
         * 该方法会移除列表中出现的所有同名 flag。</p>
         *
         * @param args 可变参数列表
         * @param name flag 名称
         * @return 如果列表中出现过该 flag 则返回 {@code true}
         */
        private static boolean takeFlag(List<String> args, String name) {
            // 同一个 flag 出现多次也只表示 true；循环移除所有重复项。
            boolean found = false;
            while (args.remove(name)) {
                found = true;
            }
            return found;
        }

        /**
         * 解析并校验 {@code --max-steps} 的值。
         *
         * @param value 命令行传入的字符串值
         * @return 合法的最大 step 数
         * @throws IllegalArgumentException 当值不是整数或不在 1..100 范围内时抛出
         */
        private static int parseMaxSteps(String value) {
            int parsed;
            try {
                // 命令行传入的是字符串，需要先解析成整数。
                parsed = Integer.parseInt(value);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("--max-steps must be between 1 and " + MAX_MAX_STEPS);
            }

            // 限制最大 step 数，避免一次 turn 无限制地调模型或执行工具。
            if (parsed < 1 || parsed > MAX_MAX_STEPS) {
                throw new IllegalArgumentException("--max-steps must be between 1 and " + MAX_MAX_STEPS);
            }
            return parsed;
        }
    }
}
