package org.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

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
                                    "analyze_repo_baseline",
                                    "Analyze a repository using the baseline (non-threaded) server variant.",
                                    "Takes maxFiles and returns a summary. (Implementation ignored for now.)",
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

                                log.info("Baseline tool called with maxFiles={}", maxFiles);

                                return McpSchema.CallToolResult.builder()
                                        .addTextContent("OK (baseline). maxFiles=" + maxFiles)
                                        .isError(false)
                                        .build();
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
    }
}
