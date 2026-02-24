package org.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        HttpServer metricsServer = null;
        ExecutorService requests = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 4);

        try {
            // -------------------------
            // Prometheus pull metrics
            // -------------------------

            // -------------------------
            // Create the registry
            PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
            String maxTODOs = args.length >= 3 ? args[3] : "0";

            // -------------------------
            // Request-level counters
            Counter reqTotal = Counter.builder("requests_total")
                    .description("Total MCP tool calls received")
                    .register(registry);

            // -------------------------
            // Request latency
            Timer reqLatency = Timer.builder("request_duration_seconds")
                    .description("End-to-end MCP tool call duration")
                    .tag("limit", maxTODOs)
                    .publishPercentileHistogram()
                    .register(registry);

            // -------------------------
            // Work completion semantics (per request)
            DistributionSummary todosCompletedPerRequest = DistributionSummary.builder("todos_completed_per_request")
                    .description("Number of TODOs completed before return or timeout")
                    .tag("limit", maxTODOs)
                    .publishPercentileHistogram()
                    .register(registry);

            DistributionSummary todosMissedPerRequest = DistributionSummary.builder("todos_missed_per_request")
                    .description("Number of TODOs missed due to timeout or cancellation")
                    .tag("limit", maxTODOs)
                    .publishPercentileHistogram()
                    .register(registry);

            // -------------------------
            // Execution control / leakage
            DistributionSummary leakedThreads = DistributionSummary.builder("leaked_threads")
                    .description("Number of threads still running after request completes")
                    .tag("limit", maxTODOs)
                    .publishPercentileHistogram()
                    .register(registry);

            String variant  = (args.length >= 5) ? args[5] : "";

            int port = "baseline".equals(variant) ? 9100 : 9101;
            log.info("Listening on port {}", port);
            registry.config().commonTags("variant", args.length >= 5 ? args[5] : "");
            metricsServer = startMetricsHttpServer(registry, port);

            HttpServer finalMetricsServer = metricsServer;

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    finalMetricsServer.stop(0);
                } catch (Exception ignored) {}
            }));

            // -------------------------
            // This section runs for benchmarking purposes
            // -------------------------
            if (args.length >= 5 && args[0].equals("--bench")) {
                int n = Integer.parseInt(args[1]);      // number of iterations
                int limit = Integer.parseInt(args[3]);  // expects --limit X
                String mode = args[5];
                ToolService requestScope = new ToolService();

                for (int i = 0; i < n; i++) {
                    requests.execute(() -> {
                        Timer.Sample sample = Timer.start(registry);
                        RequestStats result;
                        try {
                            if ("baseline".equals(mode))
                                result = requestScope.baselineToolProcess(limit);
                            else
                                result = requestScope.structuredToolProcess(limit);

                            reqTotal.increment();
                            todosCompletedPerRequest.record(result.todoCount());
                            todosMissedPerRequest.record(limit - result.todoCount());
                            log.info("Active tasks: " + result.activeTasks());
                            leakedThreads.record(result.activeTasks());
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        } finally {
                            sample.stop(reqLatency);
                        }
                    });
                }

                log.info("Created benchmark with " + n + " iterations");

                requests.shutdown();
                requests.awaitTermination(10, TimeUnit.MINUTES);

                Thread.sleep(30000);
                return;
            }

            // -------------------------
            // MCP server (the tool logic)
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
                            "java_baseline_analyzer",
                            "java_baseline_analyzer",
                            "Returns the TODOs in the repo upto the limit using an unstructured concurrency approach",
                            schema,
                            null,
                            null,
                            null
                    ))
                    .callHandler((exchange, toolReq) -> {
                        reqTotal.increment();
                        Timer.Sample sample = Timer.start(registry);

                        try {
                            Map<String, Object> arguments = toolReq.arguments();
                            int limit = ((Number) arguments.get("limit")).intValue();

                            ToolService requestScope = new ToolService();
                            RequestStats requestStats = requestScope.baselineToolProcess(limit);
                            todosMissedPerRequest.record(limit - requestStats.todoCount());

                            String result = "TODOs found = " + requestStats.todoCount() + ". Scanned " + requestStats.filesScanned() + " files";

                            return McpSchema.CallToolResult.builder()
                                    .addTextContent(result)
                                    .isError(false)
                                    .build();
                        }
                        catch (Exception e) {
                            return McpSchema.CallToolResult.builder()
                                    .addTextContent("ERROR: " + e.getMessage())
                                    .isError(true)
                                    .build();
                        }
                        finally {
                            sample.stop(reqLatency);
                        }
                    })
                    .build();

            McpServerFeatures.SyncToolSpecification analyzeRepoStructured = McpServerFeatures.SyncToolSpecification.builder()
                    .tool(new McpSchema.Tool(
                            "java_structured_analyzer",
                            "java_structured_analyzer",
                            "Returns the TODOs in the repo upto the limit using a structured concurrency approach",
                            schema,
                            null,
                            null,
                            null
                    ))
                    .callHandler((exchange, toolReq) -> {
                        reqTotal.increment();
                        Timer.Sample sample = Timer.start(registry);

                        try {
                            Map<String, Object> arguments = toolReq.arguments();
                            int limit = ((Number) arguments.get("limit")).intValue();

                            ToolService requestScope = new ToolService();
                            RequestStats requestStats = requestScope.structuredToolProcess(limit);
                            todosMissedPerRequest.record(limit - requestStats.todoCount());

                            String result = "TODOs found = " + requestStats.todoCount() + ". Scanned " + requestStats.filesScanned() + " files";

                            return McpSchema.CallToolResult.builder()
                                    .addTextContent(result)
                                    .isError(false)
                                    .build();
                        }
                        catch (Exception e) {
                            return McpSchema.CallToolResult.builder()
                                    .addTextContent("ERROR: " + e.getMessage())
                                    .isError(true)
                                    .build();
                        }
                        finally {
                            sample.stop(reqLatency);
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
                    .tools(List.of(analyzeRepoBaseline, analyzeRepoStructured))
                    .build();

            log.info("Java MCP Server ready (stdio). Waiting for calls...");

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
        }
    }

    private static HttpServer startMetricsHttpServer(PrometheusMeterRegistry registry, int port) throws IOException {
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
}
