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
import org.example.MockTool.RepoAnalyser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micrometer.core.instrument.*;
import io.micrometer.prometheusmetrics.PrometheusConfig;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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

            // Tool input schema: { "maxFiles": <int> }
            McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
                    "object",
                    Map.of(
                            "maxFiles", Map.of(
                                    "type", "integer",
                                    "minimum", 1,
                                    "description", "Maximum number of files to analyze"
                            )
                    ),
                    List.of("maxFiles"),
                    false,
                    Map.of(),
                    Map.of()
            );

            McpServerFeatures.SyncToolSpecification analyzeRepoBaseline =
                    McpServerFeatures.SyncToolSpecification.builder()
                            .tool(new McpSchema.Tool(
                                    "analyse_java_repo(Java_Baseline)",
                                    "Analyze the java files using the Java baseline server variant.",
                                    "Takes maxFiles and returns a summary of the number of lines and TODOs of selected files",
                                    schema,
                                    null,
                                    null,
                                    null
                            ))
                            .callHandler((exchange, toolReq) -> {
                                // Read maxFiles from the tool call arguments
                                // NOTE: depending on SDK version, arguments may be exposed as:
                                // - toolReq.arguments()
                                // - toolReq.params().arguments()
                                // If this doesn't compile, check the getters and adjust accordingly.
                                Map<String, Object> arguments = toolReq.arguments();
                                int maxFiles = ((Number) arguments.get("maxFiles")).intValue();

                                // Bcz of the way the Docker MCP gateway works (short bursts of images running) the singleton might be redundant.
                                RepoAnalyser repoAnalyser = new RepoAnalyser();

                                List<Path> filePaths = repoAnalyser.analyzeRepository("./MockRepository/Java", ".java", maxFiles);

                                for (Path path : filePaths) {
                                    try {
                                        repoAnalyser.analyzeFile(path);
                                    } catch (IOException e) {
                                        // Create custom exception if needed
                                        throw new RuntimeException(e);
                                    }
                                }

                                log.info("Baseline tool called with maxFiles={}", maxFiles);

                                return McpSchema.CallToolResult.builder()
                                        .addTextContent("OK (baseline). maxFiles=" + maxFiles)
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
//                                    // TODO: parse args (maxFiles) and call analyzer later
//                                    // int maxFiles = ((Number) toolReq.arguments().get("maxFiles")).intValue();
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
            throw new RuntimeException(e);
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
