package org.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Histogram;
import io.prometheus.metrics.exporter.httpserver.HTTPServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * The main class containing both the Java MCP server variants
 */
public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {

        try {
            // -------------------------
            // Prometheus pull metrics
            // -------------------------

            Counter reqTotal = Counter.builder()
                    .name("requests_total")
                    .help("Total MCP tool calls received")
                    .register();

            Histogram reqLatency = Histogram.builder()
                    .name("request_duration_seconds")
                    .help("End-to-end MCP tool call duration")
                    .register();

            Histogram todosCompletedPerRequest = Histogram.builder()
                    .name("todos_completed_per_request")
                    .help("Number of TODOs completed before return or timeout")
                    .register();

            Histogram leakedThreads = Histogram.builder()
                    .name("leaked_threads")
                    .help("Number of threads still running after request completes")
                    .register();

            String variant = (args.length >= 3) ? args[3] : "";
            int port = "baseline".equals(variant) ? 9100 : 9101;

            log.info("Listening on port {}", port);

            HTTPServer _ = HTTPServer.builder().port(port).buildAndStart();

            log.info("Metrics server running on http://0.0.0.0:{}/metrics", port);

            // -------------------------
            // This section runs for benchmarking purposes
            // -------------------------
            if (args.length >= 3 && args[0].equals("--bench")) {
                int limit = Integer.parseInt(args[1]);
                String mode = args[3];
                ToolService toolService = new ToolService();
                long time = System.currentTimeMillis();
                int index = 0;

                log.info("Running benchmark with a limit of upto {} TODOs", limit);

                while (System.currentTimeMillis() - time <= 100000) {
                    index = index == limit ? 1 : index + 1;
                    int currentLimit = index;
                    long startTime = System.nanoTime();

                    try {
                        RequestStats result = "baseline".equals(mode) ? toolService.baselineToolProcess(currentLimit) :
                                toolService.structuredToolProcess(currentLimit);

                        reqTotal.inc();
                        todosCompletedPerRequest.observe(result.todoCount());
                        leakedThreads.observe(result.activeTasks());

                        log.info("Active tasks: {}", result.activeTasks());
                    }
                    catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    finally {
                        double latency = (System.nanoTime() - startTime) / 1_000_000_000.0;
                        reqLatency.observe(latency);
                    }
                    Thread.sleep(100);
                }

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
                    .callHandler((_, toolReq) -> {
                        try {
                            Map<String, Object> arguments = toolReq.arguments();
                            int limit = ((Number) arguments.get("limit")).intValue();

                            ToolService requestScope = new ToolService();
                            RequestStats requestStats = requestScope.baselineToolProcess(limit);

                            String result = "TODOs found = " + requestStats.todoTasks() +
                                    ". Scanned " + requestStats.filesScanned() +
                                    " files. Unfinished tasks = " + requestStats.activeTasks();

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
                    .callHandler((_, toolReq) -> {
                        try {
                            Map<String, Object> arguments = toolReq.arguments();
                            int limit = ((Number) arguments.get("limit")).intValue();

                            ToolService requestScope = new ToolService();
                            RequestStats requestStats = requestScope.structuredToolProcess(limit);

                            String result = "TODOs found = " + requestStats.todoTasks() +
                                    ". Scanned " + requestStats.filesScanned() +
                                    " files. Unfinished tasks = " + requestStats.activeTasks();

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
                    })
                    .build();

            McpSyncServer _ = McpServer.sync(transport)
                    .serverInfo("Java MCP Server", "1.0.0")
                    .capabilities(McpSchema.ServerCapabilities.builder()
                            .tools(true)
                            .logging()
                            .build()
                    )
                    .tools(List.of(analyzeRepoBaseline, analyzeRepoStructured))
                    .build();

            log.info("Java MCP Server ready. Waiting for calls...");

            synchronized (Main.class) {
                Main.class.wait();
            }
        }
        catch (Exception e) {
            Thread.currentThread().interrupt();
            log.error("Error in main file: {}", e.getMessage());
        }
    }
}