package com.superagent.platform.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superagent.api.provider.ProviderModelDto;
import com.superagent.core.chat.RunCredential;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** A failed CLI check says why, not just the config warnings that happen to be printed last. */
class ProviderInferenceProbeTest {

    private static final String WARNING = "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_0\",\"type\":\"error\",\"message\":"
            + "\"Codex is ignoring 3 unrecognized configuration settings. Check for typos or deprecated settings.\\n"
            + "  user (/root/.codex/config.toml): `network_access` is ignored.\"}}";

    @Test
    void codexFailureLeadsWithTheExitCodeAndItsOwnReason() {
        String out = String.join("\n",
                "{\"type\":\"thread.started\",\"thread_id\":\"t1\"}", WARNING, "{\"type\":\"turn.started\"}",
                "2026-11-06T16:45:16Z ERROR codex_api::endpoint::responses_websocket: failed to connect",
                "{\"type\":\"error\",\"message\":\"Reconnecting... 5/5 (unexpected status 401 Unauthorized)\"}",
                "{\"type\":\"turn.failed\",\"error\":{\"message\":\"unexpected status 401 Unauthorized: Incorrect API key provided\"}}");
        assertThat(ProviderInferenceProbe.cliFailure(1, out))
                .isEqualTo("退出码 1：unexpected status 401 Unauthorized: Incorrect API key provided");

        // Nothing after the turn started (what an administrator saw): say so instead of showing the warnings.
        String cut = String.join("\n", "{\"type\":\"thread.started\",\"thread_id\":\"t1\"}", WARNING, "{\"type\":\"turn.started\"}");
        assertThat(ProviderInferenceProbe.cliFailure(143, cut)).startsWith("退出码 143（进程被终止）").contains("没有输出任何错误信息")
                .doesNotContain("unrecognized");

        // Plain output (Claude Code): its last lines.
        assertThat(ProviderInferenceProbe.cliFailure(1, "Starting\nError: Invalid API key · Please run /login\n"))
                .isEqualTo("退出码 1：Starting\nError: Invalid API key · Please run /login");
    }

    @Test
    void grokChecksTheKeyAndDiscoversAndProbesLanguageModels() throws Exception {
        var authorization = new AtomicReference<String>();
        var inference = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            try (exchange) {
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] body = """
                        {"data":[{"id":"grok-4.7"},{"id":"grok-imagine-video"},{"id":"grok-voice"}]}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.createContext("/v1/responses", exchange -> {
            try (exchange) {
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                inference.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] body = """
                        {"status":"completed","usage":{"input_tokens":10,"output_tokens":4}}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            var credential = new RunCredential("grok-credential", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "xai-test-key", false);
            var probe = new ProviderInferenceProbe(null);
            probe.checkKey("grok", null, credential);
            assertThat(authorization.get()).isEqualTo("Bearer xai-test-key");

            assertThat(new ProviderModelFetcher(null).fetch("grok", credential).models())
                    .extracting(ProviderModelDto::id).containsExactly("grok-4.7");
            probe.check("grok", "grok-4.7", credential);
            var body = new ObjectMapper().readTree(inference.get());
            assertThat(body.path("model").asText()).isEqualTo("grok-4.7");
            assertThat(body.path("store").asBoolean(true)).isFalse();
            assertThat(authorization.get()).isEqualTo("Bearer xai-test-key");
        } finally {
            server.stop(0);
        }
    }
}
