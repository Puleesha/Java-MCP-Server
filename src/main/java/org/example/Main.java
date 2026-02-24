package org.example;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import io.prometheus.metrics.exporter.pushgateway.PushGateway;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    // Push interval in seconds — match your Prometheus scrape_interval
    private static final int PUSH_INTERVAL_SECONDS = 15;

    public static void main(String[] args) {
        ScheduledExecutorService pusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "metrics-push");
            t.setDaemon(true);
            return t;
        });
        ExecutorService requests = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 4);
        // TODO: Add metric for tasks created (if limit is small it might change time to time)

        try {
            // -------------------------
            // Prometheus push metrics
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

            String variant = (args.length >= 5) ? args[5] : "";
            registry.config().commonTags("variant", variant);

            // -------------------------
            // Push gateway setup
            // -------------------------
            // Job name encodes the variant so baseline/structured metrics are
            // stored under separate grouping keys in the Pushgateway — replacing
            // the role that separate ports (9100/9101) played in the pull model.
            String pushgatewayUrl = System.getenv().getOrDefault("PUSHGATEWAY_URL", "localhost:9091");
            String jobName = "mcp_server_" + (variant.isEmpty() ? "default" : variant);
            String instanceId = ManagementFactory.getRuntimeMXBean().getName(); // "pid@host"

            log.info("Pushing metrics to {} as job='{}' instance='{}'", pushgatewayUrl, jobName, instanceId);

            PushGateway pushGateway = PushGateway.builder()
                    .address(pushgatewayUrl)
                    .registry(registry.getPrometheusRegistry())
                    .job(jobName)
                    .groupingKey("instance", instanceId)
                    .build();

            pusher.scheduleAtFixedRate(() -> {
                try {
                    pushGateway.pushAdd();
                } catch (Exception e) {
                    log.warn("Pushgateway push failed: {}", e.getMessage());
                }
            }, 0, PUSH_INTERVAL_SECONDS, TimeUnit.SECONDS);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                pusher.shutdown();
                try { pushGateway.pushAdd(); } catch (Exception ignored) {}
                try { pushGateway.delete();  } catch (Exception ignored) {}
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

                // Keep running long enough for the final scheduled push to fire
                // before the shutdown hook cleans up.
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
        }
        finally {
            pusher.shutdown();
        }
    }
}