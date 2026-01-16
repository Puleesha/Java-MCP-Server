package org.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        HttpServer metricsServer = null;

        try {
            // -------------------------
            // Prometheus (PULL) metrics
            // -------------------------
            PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

            Counter reqTotal = Counter.builder("mcp_tool_requests_total")
                    .description("Total tool calls received")
                    .register(registry);

            Counter reqErrors = Counter.builder("mcp_tool_request_errors_total")
                    .description("Total tool calls failed")
                    .register(registry);

            Timer reqLatency = Timer.builder("mcp_tool_request_duration_seconds")
                    .description("Tool call duration in seconds")
                    .publishPercentileHistogram()
                    .register(registry);

            AtomicInteger active = new AtomicInteger(0);
            registry.gauge("mcp_active_requests", active);

            metricsServer = startMetricsHttpServer(registry);

            // Stop metrics server on shutdown (nice-to-have)
            HttpServer finalMetricsServer = metricsServer;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    finalMetricsServer.stop(0);
                } catch (Exception ignored) {}
            }));

            // -------------------------
            // MCP server (your logic)
            // -------------------------
            JacksonMcpJsonMapper mapper = new JacksonMcpJsonMapper(new ObjectMapper());
            StdioServerTransportProvider transport = new StdioServerTransportProvider(mapper);

            // Tool input schema: { "limit": <int> }
            McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
                "object",
                Map.of(
                    "limit", Map.of(
                        "type", "integer",
                        "minimum", 1,
                        "description", "Maximum number of files to analyze"
                    )
                ),
                List.of("limit"),
                false,
                Map.of(),
                Map.of()
            );

            McpServerFeatures.SyncToolSpecification analyzeRepoBaseline = McpServerFeatures.SyncToolSpecification.builder()
                .tool(new McpSchema.Tool(
                    "analyze_java_files_JB",
                    "analyze_java_files_JB",
                    "Returns the number of lines and TODOs of selected files",
                    schema,
                    null,
                    null,
                    null
                ))
                .callHandler((exchange, toolReq) -> {
                    // ----- metrics instrumentation (only addition) -----
                    reqTotal.increment();
                    active.incrementAndGet();
                    Timer.Sample sample = Timer.start(registry);

                    try {
                        // ---- your MCP logic untouched ----
                        Map<String, Object> arguments = toolReq.arguments();
                        int limit = ((Number) arguments.get("limit")).intValue();

                        String result = RequestScope.analyseRepoTool(limit);

                        return McpSchema.CallToolResult.builder()
                                .addTextContent(result)
                                .isError(false)
                                .build();
                    }
                    catch (Exception e) {
                        reqErrors.increment();
                        return McpSchema.CallToolResult.builder()
                                .addTextContent("ERROR: " + e.getMessage())
                                .isError(true)
                                .build();
                    }
                    finally {
                        sample.stop(reqLatency);
                        active.decrementAndGet();
                    }
                })
                .build();

            McpSyncServer server = McpServer.sync(transport)
                    .serverInfo("baseline-java-mcp", "1.0.0")
                    .capabilities(McpSchema.ServerCapabilities.builder()
                            .tools(true)
                            .logging()
                            .build()
                    )
                    .tools(List.of(analyzeRepoBaseline))
                    .build();

            log.info("Baseline MCP Server ready (stdio). Waiting for calls...");

            // Keep alive for the gateway session
            synchronized (Main.class) {
                Main.class.wait();
            }
        }
        catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted, shutting down.");
        }
        catch (Exception e) {
            log.error("Server error", e);

            if (metricsServer != null)
                try { metricsServer.stop(0); } catch (Exception ignored) {}

            System.exit(1);
        }
    }

    // Uses system property -DMETRICS_PORT=9100, or env METRICS_PORT, default 9100.
    private static HttpServer startMetricsHttpServer(PrometheusMeterRegistry registry) throws IOException {
        int port = getIntConfig();

        // 0.0.0.0 so Prometheus outside the container can scrape it
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);

        server.createContext("/metrics", exchange -> {
            byte[] body = registry.scrape().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        server.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "metrics-http");
            t.setDaemon(true); // don't prevent shutdown
            return t;
        }));

        server.start();
        log.info("Metrics server running on http://0.0.0.0:{}/metrics", port);
        return server;
    }

    private static int getIntConfig() {
        String v = System.getProperty("METRICS_PORT");
        if (v == null || v.isBlank()) v = System.getenv("METRICS_PORT");
        if (v == null || v.isBlank()) return 9100;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return 9100;
        }
    }
}
