package com.superagent.runtime.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superagent.core.chat.AgentRunRequest;
import com.superagent.core.chat.AgentType;
import com.superagent.core.provider.ProviderCatalog;
import com.superagent.core.usage.TokenUsage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** xAI's Grok Build headless client, with the same event format as Claude Code. */
@Component
public class GrokCliAgentRunner extends JsonLineCliRunner {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CONFIGURED_MODEL = "superagent-grok";
    private static final String KEY_ENV = "XAI_API_KEY";
    private static final Map<String, String> BUILTIN_TOOLS = Map.of(
            "Read", "read_file", "Glob", "list_dir", "Grep", "grep", "LS", "list_dir",
            "Edit", "search_replace", "Write", "write", "Bash", "run_terminal_command");

    private final Path runtimeDir;

    public GrokCliAgentRunner(@Value("${superagent.agent.grok.command:grok}") String command,
                              @Value("${superagent.agent.grok.timeout-seconds:600}") long timeoutSeconds,
                              @Value("${superagent.agent.grok.config-dir:./data/runtime/grok}") String runtimeDir) {
        super(command, timeoutSeconds);
        this.runtimeDir = Path.of(runtimeDir).toAbsolutePath().normalize();
    }

    @Override
    public AgentType type() {
        return AgentType.GROK;
    }

    @Override
    protected String precondition(AgentRunRequest request) {
        String problem = super.precondition(request);
        if (problem != null) {
            return problem;
        }
        if (request.model() == null || request.model().isBlank()) {
            return "Grok Build 需要指定模型，请在对话框或任务里选择一个已通过检查的 Grok 模型";
        }
        return request.images().isEmpty() ? null : "当前 Grok 接入仅支持文本，请移除图片或选择支持图片的模型";
    }

    @Override
    protected List<String> buildCommand(AgentRunRequest request, String resumeId, List<Path> cleanup) throws IOException {
        Path promptFile = Files.createTempFile("superagent-grok-", ".txt");
        cleanup.add(promptFile);
        Files.writeString(promptFile, prompt(request, resumeId));
        List<String> cmd = new ArrayList<>(List.of(command(), "--prompt-file", promptFile.toString(),
                "--model", CONFIGURED_MODEL, "--verbatim", "--no-auto-update", "--no-memory", "--no-subagents",
                "--output-format", "streaming-messages-json", "--include-partial-messages",
                "--sandbox", canWrite(request) ? "strict" : "read-only"));
        if (resumeId != null) {
            cmd.addAll(List.of("--resume", resumeId));
        }
        if (request.skipPermissions()) {
            cmd.add("--always-approve");
            return cmd;
        }
        cmd.addAll(List.of("--permission-mode", "dontAsk"));
        List<String> tools = request.allowedTools() == null ? List.of() : request.allowedTools();
        List<String> builtins = tools.stream().map(BUILTIN_TOOLS::get).filter(java.util.Objects::nonNull).distinct().toList();
        cmd.addAll(List.of("--tools", String.join(",", builtins)));
        builtins.forEach(tool -> cmd.addAll(List.of("--allow", tool)));
        if (!CodexCliAgentRunner.mcpServers(request).isEmpty()) {
            cmd.addAll(List.of("--allow", "MCPTool"));
        }
        return cmd;
    }

    @Override
    protected String stdin(AgentRunRequest request, String resumeId) {
        return null;
    }

    @Override
    protected Map<String, String> environment(AgentRunRequest request) throws IOException {
        Path home = runtimeDir.resolve(UUID.nameUUIDFromBytes(
                (request.workspaceDir() + "\n" + request.sessionId()).getBytes(StandardCharsets.UTF_8)).toString());
        Files.createDirectories(home, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        List<CodexCliAgentRunner.McpServer> servers = CodexCliAgentRunner.mcpServers(request);
        StringBuilder policy = new StringBuilder("enable_all_project_mcp_servers = false\nallowed_mcp_servers = [\n");
        for (CodexCliAgentRunner.McpServer server : servers) {
            policy.append("    { server_url = ").append(JSON.writeValueAsString(server.url())).append(" },\n");
        }
        policy.append("]\n");
        writeToml(home.resolve("requirements.toml"), policy.toString());
        writeToml(home.resolve("config.toml"), config(request, servers));

        Map<String, String> env = new LinkedHashMap<>();
        env.put("GROK_HOME", home.toString());
        env.put(KEY_ENV, request.credential().apiKey());
        for (CodexCliAgentRunner.McpServer server : servers) {
            if (server.token() != null) {
                env.put(server.tokenEnvVar(), server.token());
            }
        }
        return env;
    }

    private String config(AgentRunRequest request, List<CodexCliAgentRunner.McpServer> servers) throws IOException {
        String base = request.credential().baseUrl();
        if (base == null || base.isBlank()) {
            base = ProviderCatalog.find("grok").orElseThrow().access().defaultBaseUrl();
        }
        StringBuilder config = new StringBuilder("""
                [cli]
                use_leader = false

                [compat.claude]
                mcps = false
                hooks = false

                [compat.cursor]
                mcps = false
                hooks = false

                [compat.codex]
                hooks = false

                [models]
                default = "superagent-grok"

                [model.superagent-grok]
                api_backend = "responses"
                env_key = "XAI_API_KEY"
                """);
        config.append("model = ").append(JSON.writeValueAsString(request.model())).append('\n')
                .append("base_url = ").append(JSON.writeValueAsString(base.replaceFirst("/+$", ""))).append('\n');
        for (CodexCliAgentRunner.McpServer server : servers) {
            config.append("\n[mcp_servers.").append(server.name()).append("]\n")
                    .append("url = ").append(JSON.writeValueAsString(server.url())).append('\n');
            if (server.token() != null) {
                config.append("headers = { Authorization = \"Bearer ${").append(server.tokenEnvVar()).append("}\" }\n");
            }
        }
        return config.toString();
    }

    private static void writeToml(Path target, String text) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), "config-", ".toml");
        try {
            Files.writeString(temporary, text);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    protected LineParser newParser() {
        ClaudeStreamParser parser = new ClaudeStreamParser();
        return new LineParser() {
            private List<TokenUsage> totals = List.of();

            @Override
            public List<Line> parse(String line) {
                return parser.parse(line).map(event -> {
                    if (!event.tokens().isEmpty()) {
                        totals = event.tokens();
                    }
                    return List.of(new Line(event.type(), event.payload()));
                }).orElseGet(List::of);
            }

            @Override
            public List<TokenUsage> tokens(String model) {
                return (totals.isEmpty() ? parser.unreportedTokens() : totals).stream()
                        .map(usage -> usage.model() == null || CONFIGURED_MODEL.equals(usage.model())
                                ? new TokenUsage(model, usage.input(), usage.cacheRead(), usage.cacheWrite(),
                                        usage.cacheWrite1h(), usage.output(), usage.reasoning(), usage.cliCostUsd())
                                : usage)
                        .toList();
            }
        };
    }
}
