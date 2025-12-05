package org.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.example.MockTool.RepoAnalyser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Random;

public class Main
{
    private static final Logger log = LoggerFactory.getLogger(Main.class);
    private static final String[] QUOTES = new String[] {
            "To be or not to be, that's the question. — Me",
            "To be and not to be, that's the answer. — Some wannabe philosopher",
            "Simplicity is the soul of efficiency. — Austin Freeman"
    };
    private static final Random RNG = new Random();

    public static void main(String[] args)
    {
        try
        {
            JacksonMcpJsonMapper mapper = new JacksonMcpJsonMapper(new ObjectMapper());

            StdioServerTransportProvider transport = new StdioServerTransportProvider(mapper);

            McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
                "object",           // type
                    Map.of(),            // properties
                    List.of(),           // required
                    false,               // additionalProperties
                    Map.of(),            // $defs
                    Map.of()             // definitions
            );

            McpServerFeatures.SyncToolSpecification callJavaRepo = McpServerFeatures.SyncToolSpecification.builder()
                    .tool(new McpSchema.Tool(
                            "java_repo_1_baseline",
                            "Analyse Java repository 1 in baseline server",
                            "Analyses a given number of files using the baseline Java server",
                            schema,
                            null,
                            null,
                            null
                    ))
                    .callHandler((exchange, toolReq) -> {
                        RepoAnalyser analyser = new RepoAnalyser();

                        return McpSchema.CallToolResult.builder()
                                .addTextContent("")  // -> content: [{ "type": "text", "text": "..." }]
                                .isError(false)
                                .build();
                    })
                    .build();

            McpSyncServer server = McpServer.sync(transport)
                    .serverInfo("my-mcp-server", "0.0.1")
                    .capabilities(McpSchema.ServerCapabilities.builder()
                            .tools(true)
                            .logging()
                            .build()
                    )
                    .tools(List.of(callJavaRepo))
                    .build();

//            server.addTool(getQuote);

            log.info("my-mcp-server ready (stdio). Waiting for calls...");

            // Keep the JVM alive so the gateway can call us
            synchronized (Main.class) { Main.class.wait(); }

        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            log.warn("Interrupted, shutting down.");
        }
        catch (Exception e)
        {
            log.error("Server error", e);
            System.exit(1);
        }
    }
}
