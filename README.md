## Java MCP Server

This project represents the Java MCP server variants made for the Final Year Research Project.

This server variants use either an <strong> unstructured concurrency approach </strong> or a <strong>structured concurrency approach</strong> for handling multiple threads per request.

It includes a mock repository for the file analysis, which include three folders:

<ul>
  <li><strong>Java -</strong> Contains a section of the apache-commons-lang library</li>
  <li><strong>Rust -</strong> Contains a secrion of the Rust Compiler </li>
  <li><strong>JavaScript -</strong> Contains a section of the Facebook React source code</li>
</ul>

The full repositories can be accessed in the following links:

<ul>
  <li><strong>Java -</strong> https://github.com/apache/commons-lang </li>
  <li><strong>Rust -</strong> https://github.com/rust-lang/rust </li>
  <li><strong>JavaScript -</strong> https://github.com/facebook/react/ </li>
</ul>

## Setup Instructions 

1.  Ensure Docker Desktop is installed and an MCP client (e.g. Claude) is connected.

2.  Go to the project root and create the Docker image:
```bash
docker build -t java-mcp-server .
```

2. Open a termincal window and create a new MCP catalog file:
```bash
nano ~/.docker/mcp/catalogs/customCatalog.yaml
```

3. Create the YAML file with the following code
```yaml
version: 2
name: custom
displayName: Custom MCP Catalog
registry:
  java-mcp-server:
    title: Java MCP Server
    description: Local Java MCP server image
    type: server
    image: java-mcp-server:latest
    ref: ""
  rust-mcp-server:
    title: Rust MCP Server
    description: Local Rust MCP server image
    type: server
    image: rust-mcp-server:latest
    ref: ""
```

3. Update the Docker MCP registry with our custom server images.
```bash
nano ~/.docker/mcp/registry.yaml
```
```yaml
registry:
  java-mcp-server:
    ref: ""
  rust-mcp-server:
    ref: ""
```

4. Update the Claude config file with the newly created configuration files.
```bash
open -a TextEdit "/Users/pradeep/Library/Application Support/Claude/claude_desktop_config.json"
```
```json
{
  "mcpServers": {
    "mcp-toolkit-gateway": {
      "command": "docker",
      "args": [
        "run",
        "-i",
        "--rm",
        "-v",
        "/var/run/docker.sock:/var/run/docker.sock",
        "-v",
        "/Users/{USER_NAME}/.docker/mcp:/mcp",
        "docker/mcp-gateway",
        "--catalog=/mcp/catalogs/docker-mcp.yaml",
        "--catalog=/mcp/catalogs/customCatalog.yaml",
        "--config=/mcp/config.yaml",
        "--registry=/mcp/registry.yaml",
        "--tools-config=/mcp/tools.yaml",
        "--transport=stdio"
      ]
    }
  },
  "preferences": {
    "coworkScheduledTasksEnabled": false,
    "sidebarMode": "chat",
    "coworkWebSearchEnabled": true,
    "ccdScheduledTasksEnabled": false
  }
}
```

5. Open Claude Desktop and view the connectors to see the new MCP servers.

6. Ensure that Grafana is conencted to a Prometheus data source via http://host.docker.internal:9090
7. 
