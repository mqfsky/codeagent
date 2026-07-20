package minicode.config;

import minicode.mcp.McpServerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeConfigLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void explicitMockProviderDoesNotRequireRealProviderAuth() {
        RuntimeConfig config = RuntimeConfigLoader.load(new RuntimeConfigLoader.Input(
                tempDir.resolve("home"),
                tempDir.resolve("workspace"),
                Map.of("CODEAGENT_PROVIDER", "mock", "CODEAGENT_MODEL", "mock-model")
        ));

        assertEquals(ProviderKind.MOCK, config.provider());
        assertEquals("mock-model", config.model());
        assertTrue(config.apiKey().isEmpty());
        assertTrue(config.authToken().isEmpty());
    }

    @Test
    void realProviderRequiresModelAndAuthInsteadOfFallingBackToMock() {
        RuntimeConfigException missingModel = assertThrows(RuntimeConfigException.class, () ->
                RuntimeConfigLoader.load(new RuntimeConfigLoader.Input(
                        tempDir.resolve("home"),
                        tempDir.resolve("workspace"),
                        Map.of("CODEAGENT_PROVIDER", "anthropic")
                )));
        RuntimeConfigException missingAuth = assertThrows(RuntimeConfigException.class, () ->
                RuntimeConfigLoader.load(new RuntimeConfigLoader.Input(
                        tempDir.resolve("home"),
                        tempDir.resolve("workspace"),
                        Map.of("CODEAGENT_PROVIDER", "anthropic", "CODEAGENT_MODEL", "claude-test")
                )));

        assertTrue(missingModel.getMessage().contains("No model configured"));
        assertTrue(missingAuth.getMessage().contains("No auth configured"));
    }

    @Test
    void settingsAndEnvAreMergedWithEnvTakingPrecedence() throws Exception {
        Path home = tempDir.resolve("home");
        Path cwd = tempDir.resolve("workspace");
        Files.createDirectories(home);
        Files.createDirectories(cwd.resolve(".codeagent"));
        Files.writeString(home.resolve("settings.json"), """
                {
                  "provider": "anthropic",
                  "model": "claude-from-settings",
                  "baseUrl": "https://settings.example",
                  "apiKey": "settings-key",
                  "maxOutputTokens": 1024,
                  "contextWindow": 200000
                }
                """);
        Files.writeString(cwd.resolve(".codeagent").resolve("settings.json"), """
                {
                  "model": "claude-from-project",
                  "baseUrl": "https://project.example",
                  "apiKey": "project-key",
                  "contextWindow": 100000
                }
                """);

        RuntimeConfig config = RuntimeConfigLoader.load(new RuntimeConfigLoader.Input(
                home,
                cwd,
                Map.of(
                        "CODEAGENT_MODEL", "claude-from-env",
                        "ANTHROPIC_AUTH_TOKEN", "env-token",
                        "CODEAGENT_MAX_OUTPUT_TOKENS", "2048"
                )
        ));

        assertEquals(ProviderKind.ANTHROPIC, config.provider());
        assertEquals("claude-from-env", config.model());
        assertEquals("https://project.example", config.baseUrl());
        assertEquals("project-key", config.apiKey().orElseThrow());
        assertEquals("env-token", config.authToken().orElseThrow());
        assertEquals(2048, config.maxOutputTokens().orElseThrow());
        assertEquals(100000, config.contextWindow().orElseThrow());
        assertTrue(config.maxSteps().isEmpty());
        assertEquals(java.time.Duration.ofSeconds(300), config.providerTimeout());
        assertTrue(config.sourceSummary().contains(home.resolve("settings.json").toString()));
        assertTrue(config.sourceSummary().contains(cwd.resolve(".codeagent").resolve("settings.json").toString()));
    }

    @Test
    void settingsEnvIsMergedLikeTsRuntimeConfig() throws Exception {
        Path home = tempDir.resolve("home");
        Path cwd = tempDir.resolve("workspace");
        Files.createDirectories(home);
        Files.createDirectories(cwd.resolve(".codeagent"));
        Files.writeString(home.resolve("settings.json"), """
                {
                  "model": "home-top-model",
                  "apiKey": "home-top-key",
                  "env": {
                    "ANTHROPIC_MODEL": "home-env-model",
                    "ANTHROPIC_API_KEY": "home-env-key",
                    "ANTHROPIC_BASE_URL": "https://home-env.example",
                    "CODEAGENT_MAX_OUTPUT_TOKENS": 1111
                  }
                }
                """);
        Files.writeString(cwd.resolve(".codeagent").resolve("settings.json"), """
                {
                  "model": "cwd-top-model",
                  "apiKey": "cwd-top-key",
                  "env": {
                    "ANTHROPIC_MODEL": "cwd-env-model",
                    "ANTHROPIC_API_KEY": "cwd-env-key",
                    "CODEAGENT_CONTEXT_WINDOW": 222222
                  }
                }
                """);

        RuntimeConfig config = RuntimeConfigLoader.load(new RuntimeConfigLoader.Input(
                home,
                cwd,
                Map.of("CODEAGENT_MAX_OUTPUT_TOKENS", "3333")
        ));

        assertEquals("cwd-env-model", config.model());
        assertEquals("cwd-env-key", config.apiKey().orElseThrow());
        assertEquals("https://home-env.example", config.baseUrl());
        assertEquals(3333, config.maxOutputTokens().orElseThrow());
        assertEquals(222222, config.contextWindow().orElseThrow());
    }

    @Test
    void providerTimeoutDefaultsToFiveMinutesAndCanBeOverridden() throws Exception {
        Path home = tempDir.resolve("home");
        Path cwd = tempDir.resolve("workspace");
        Files.createDirectories(home);
        Files.createDirectories(cwd.resolve(".codeagent"));
        Files.writeString(home.resolve("settings.json"), """
                {
                  "provider": "anthropic",
                  "model": "home-model",
                  "authToken": "home-token",
                  "providerTimeoutSeconds": 180
                }
                """);
        Files.writeString(cwd.resolve(".codeagent").resolve("settings.json"), """
                {
                  "providerTimeoutSeconds": 240
                }
                """);

        RuntimeConfig defaultConfig = RuntimeConfigLoader.load(new RuntimeConfigLoader.Input(
                tempDir.resolve("default-home"),
                tempDir.resolve("default-workspace"),
                Map.of(
                        "CODEAGENT_PROVIDER", "anthropic",
                        "ANTHROPIC_MODEL", "env-model",
                        "ANTHROPIC_AUTH_TOKEN", "env-token"
                )
        ));
        RuntimeConfig settingsConfig = RuntimeConfigLoader.load(new RuntimeConfigLoader.Input(home, cwd, Map.of()));
        RuntimeConfig envConfig = RuntimeConfigLoader.load(new RuntimeConfigLoader.Input(home, cwd,
                Map.of("CODEAGENT_PROVIDER_TIMEOUT_SECONDS", "360")));

        assertEquals(java.time.Duration.ofSeconds(300), defaultConfig.providerTimeout());
        assertEquals(java.time.Duration.ofSeconds(240), settingsConfig.providerTimeout());
        assertEquals(java.time.Duration.ofSeconds(360), envConfig.providerTimeout());
    }

    @Test
    void maxStepsCanBeConfiguredFromSettingsWithProjectOverride() throws Exception {
        Path home = tempDir.resolve("home");
        Path cwd = tempDir.resolve("workspace");
        Files.createDirectories(home);
        Files.createDirectories(cwd.resolve(".codeagent"));
        Files.writeString(home.resolve("settings.json"), """
                {
                  "provider": "mock",
                  "model": "mock-model",
                  "maxSteps": 24
                }
                """);
        Files.writeString(cwd.resolve(".codeagent").resolve("settings.json"), """
                {
                  "maxSteps": 48
                }
                """);

        RuntimeConfig config = RuntimeConfigLoader.load(new RuntimeConfigLoader.Input(home, cwd, Map.of()));

        assertEquals(48, config.maxSteps().orElseThrow());
    }

    @Test
    void settingsLoadMcpServersFromHomeAndProjectWithProjectOverride() throws Exception {
        Path home = tempDir.resolve("home");
        Path cwd = tempDir.resolve("workspace");
        Files.createDirectories(home);
        Files.createDirectories(cwd.resolve(".codeagent"));
        Files.writeString(home.resolve("settings.json"), """
                {
                  "provider": "mock",
                  "model": "mock-model",
                  "mcpServers": {
                    "fake": {
                      "command": "home-command",
                      "args": ["--home"],
                      "env": { "HOME_ONLY": "1" },
                      "enabled": true
                    },
                    "disabled": {
                      "command": "disabled-command",
                      "enabled": false
                    }
                  }
                }
                """);
        Files.writeString(cwd.resolve(".codeagent").resolve("settings.json"), """
                {
                  "mcpServers": {
                    "fake": {
                      "command": "project-command",
                      "args": ["--project"],
                      "env": { "PROJECT_ONLY": "2" },
                      "cwd": "tools"
                    }
                  }
                }
                """);

        RuntimeConfig config = RuntimeConfigLoader.load(new RuntimeConfigLoader.Input(home, cwd, Map.of()));

        assertEquals(2, config.mcpServers().size());
        McpServerConfig fake = config.mcpServers().get("fake");
        assertEquals("project-command", fake.command());
        assertEquals(List.of("--project"), fake.args());
        assertEquals(Map.of("HOME_ONLY", "1", "PROJECT_ONLY", "2"), fake.env());
        assertEquals(Optional.of("tools"), fake.cwd());
        assertTrue(fake.enabled());
        assertEquals(McpServerConfig.TransportKind.STDIO, fake.transportKind());
        assertFalse(config.mcpServers().get("disabled").enabled());
    }

    @Test
    void httpServerHeadersMergeAndExpandEnvironmentWithoutLeakingIntoSummary() throws Exception {
        Path home = tempDir.resolve("home");
        Path cwd = tempDir.resolve("workspace");
        Files.createDirectories(home);
        Files.createDirectories(cwd.resolve(".codeagent"));
        Files.writeString(home.resolve("settings.json"), """
                {
                  "provider": "mock",
                  "model": "mock-model",
                  "mcpServers": {
                    "remote": {
                      "url": "https://user:password@example.com/mcp?token=hidden#fragment",
                      "headers": {
                        "Authorization": "Bearer ${MCP_TOKEN}",
                        "X-Shared": "home"
                      }
                    }
                  }
                }
                """);
        Files.writeString(cwd.resolve(".codeagent").resolve("settings.json"), """
                {
                  "mcpServers": {
                    "remote": {
                      "headers": {
                        "authorization": "Bearer ${PROJECT_TOKEN}",
                        "x-shared": "project",
                        "X-Combined": "${TOKEN_PREFIX}-${MCP_TOKEN}",
                        "X-Unresolved": "Bearer ${MISSING_TOKEN}"
                      }
                    }
                  }
                }
                """);

        RuntimeConfig config = RuntimeConfigLoader.load(new RuntimeConfigLoader.Input(
                home, cwd, Map.of(
                        "MCP_TOKEN", "secret-token",
                        "PROJECT_TOKEN", "project-secret",
                        "TOKEN_PREFIX", "prefix")));

        McpServerConfig remote = config.mcpServers().get("remote");
        assertEquals(McpServerConfig.TransportKind.STREAMABLE_HTTP, remote.transportKind());
        assertEquals(Optional.of("https://user:password@example.com/mcp?token=hidden#fragment"), remote.url());
        assertEquals(Map.of(
                "authorization", "Bearer project-secret",
                "x-shared", "project",
                "X-Combined", "prefix-secret-token",
                "X-Unresolved", "Bearer ${MISSING_TOKEN}"
        ), remote.headers());
        assertFalse(remote.headers().containsKey("Authorization"));
        assertEquals("https://example.com/mcp", remote.endpointSummary());
        assertFalse(remote.endpointSummary().contains("secret-token"));
        assertFalse(remote.endpointSummary().contains("project-secret"));
        assertFalse(remote.endpointSummary().contains("${MISSING_TOKEN}"));
    }

    @Test
    void projectCanSwitchBothDirectionsBetweenStdioAndHttpWithoutInheritedTransportFields() throws Exception {
        Path home = tempDir.resolve("home");
        Path cwd = tempDir.resolve("workspace");
        Files.createDirectories(home);
        Files.createDirectories(cwd.resolve(".codeagent"));
        Files.writeString(home.resolve("settings.json"), """
                {
                  "provider": "mock",
                  "model": "mock-model",
                  "mcpServers": {
                    "to-http": {
                      "command": "home-command",
                      "args": ["--home"],
                      "cwd": "home-tools",
                      "env": { "HOME_ENV": "1" }
                    },
                    "to-stdio": {
                      "url": "https://home.example/mcp",
                      "headers": { "Authorization": "home-secret" }
                    }
                  }
                }
                """);
        Files.writeString(cwd.resolve(".codeagent").resolve("settings.json"), """
                {
                  "mcpServers": {
                    "to-http": {
                      "url": "https://project.example/mcp",
                      "headers": { "X-Project": "2" }
                    },
                    "to-stdio": {
                      "command": "project-command",
                      "args": ["--project"],
                      "cwd": "project-tools",
                      "env": { "PROJECT_ENV": "2" }
                    }
                  }
                }
                """);

        RuntimeConfig config = RuntimeConfigLoader.load(new RuntimeConfigLoader.Input(home, cwd, Map.of()));

        McpServerConfig toHttp = config.mcpServers().get("to-http");
        assertEquals(McpServerConfig.TransportKind.STREAMABLE_HTTP, toHttp.transportKind());
        assertEquals("", toHttp.command());
        assertEquals(List.of(), toHttp.args());
        assertEquals(Optional.empty(), toHttp.cwd());
        assertEquals(Map.of(), toHttp.env());
        assertEquals(Map.of("X-Project", "2"), toHttp.headers());

        McpServerConfig toStdio = config.mcpServers().get("to-stdio");
        assertEquals(McpServerConfig.TransportKind.STDIO, toStdio.transportKind());
        assertEquals("project-command", toStdio.command());
        assertEquals(Optional.empty(), toStdio.url());
        assertEquals(Map.of(), toStdio.headers());
        assertEquals(Map.of("PROJECT_ENV", "2"), toStdio.env());
    }

    @Test
    void invalidMcpEndpointsRemainIsolatedConfigurationEntries() throws Exception {
        Path home = tempDir.resolve("home");
        Path cwd = tempDir.resolve("workspace");
        Files.createDirectories(home);
        Files.createDirectories(cwd);
        Files.writeString(home.resolve("settings.json"), """
                {
                  "provider": "mock",
                  "model": "mock-model",
                  "mcpServers": {
                    "conflict": {
                      "command": "stdio-command",
                      "url": "https://example.com/mcp"
                    },
                    "invalid-url": {
                      "url": "ftp://user:password@example.com/private?token=hidden"
                    },
                    "missing": {
                      "enabled": true
                    },
                    "disabled": {
                      "enabled": false
                    }
                  }
                }
                """);

        RuntimeConfig config = RuntimeConfigLoader.load(new RuntimeConfigLoader.Input(home, cwd, Map.of()));

        assertEquals(4, config.mcpServers().size());
        assertEquals(McpServerConfig.TransportKind.INVALID,
                config.mcpServers().get("conflict").transportKind());
        assertEquals("stdio-command", config.mcpServers().get("conflict").command());
        assertEquals(Optional.of("https://example.com/mcp"), config.mcpServers().get("conflict").url());

        McpServerConfig invalidUrl = config.mcpServers().get("invalid-url");
        assertEquals(McpServerConfig.TransportKind.INVALID, invalidUrl.transportKind());
        assertEquals("invalid MCP endpoint configuration", invalidUrl.endpointSummary());
        assertFalse(invalidUrl.endpointSummary().contains("password"));
        assertFalse(invalidUrl.endpointSummary().contains("hidden"));

        assertEquals(McpServerConfig.TransportKind.INVALID,
                config.mcpServers().get("missing").transportKind());
        assertFalse(config.mcpServers().get("disabled").enabled());
    }
}
