package org.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micrometer.core.instrument.*;
import io.micrometer.prometheusmetrics.PrometheusConfig;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;

public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        try {
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
                    // Read limit from the tool call arguments
                    // NOTE: depending on SDK version, arguments may be exposed as:
                    // - toolReq.arguments()
                    // - toolReq.params().arguments()
                    // If this doesn't compile, check the getters and adjust accordingly.

                    Map<String, Object> arguments = toolReq.arguments();
                    int limit = ((Number) arguments.get("limit")).intValue();

                    String result = RequestScope.analyseRepoTool(limit);

                    return McpSchema.CallToolResult.builder()
                        .addTextContent(result)
                        .isError(false)
                        .build();

                    /*
                     *
                     * The Metics and other prometheus and grafana stuff
                     *
                     */

//                                active.incrementAndGet();
//                                long start = System.nanoTime();
//                                reqTotal.increment();
//
//                                try {
//                                    // TODO: parse args (limit) and call analyzer later
//                                    // int limit = ((Number) toolReq.arguments().get("limit")).intValue();
//
//                                    return McpSchema.CallToolResult.builder()
//                                            .addTextContent("OK")
//                                            .isError(false)
//                                            .build();
//                                } catch (Exception e) {
//                                    reqErrors.increment();
//                                    return McpSchema.CallToolResult.builder()
//                                            .addTextContent("ERROR: " + e.getMessage())
//                                            .isError(true)
//                                            .build();
//                                } finally {
//                                    reqLatency.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
//                                    active.decrementAndGet();
//                                }
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
            System.exit(1);
        }

        /*
         *
         * The Metics and other prometheus and grafana stuff
         *
         */

        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        // Minimal metrics you need
        Counter reqTotal = Counter.builder("mcp_tool_requests_total")
                .description("Total tool calls received")
                .register(registry);

        Counter reqErrors = Counter.builder("mcp_tool_request_errors_total")
                .description("Total tool calls failed")
                .register(registry);

        Timer reqLatency = Timer.builder("mcp_tool_request_duration")
                .description("Tool call duration")
                .publishPercentileHistogram()
                .register(registry);

        AtomicInteger active = registry.gauge("mcp_active_requests", new java.util.concurrent.atomic.AtomicInteger(0));

        // Start a tiny HTTP metrics server
        int metricsPort = Integer.parseInt(System.getProperty("METRICS_PORT", "9100"));
        HttpServer metricsServer = null;
        try {
            metricsServer = HttpServer.create(new InetSocketAddress(metricsPort), 0);
        } catch (IOException e) {
            log.error("Server error", e);
            System.exit(1);
        }
        metricsServer.createContext("/metrics", exchange -> {
            byte[] body = registry.scrape().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; version=0.0.4");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
        });
        metricsServer.setExecutor(Executors.newSingleThreadExecutor());
        metricsServer.start();

        log.info("Metrics server running on http://localhost:{}/metrics", metricsPort);
    }
}
