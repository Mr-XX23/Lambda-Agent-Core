# Deployment integrations

## JDK HTTP server

`AgentHttpServer` exposes `POST /agent/run` and, when workflows are registered,
`POST /workflow/run`. Pass `HttpServerConfig` with a bearer token and request/response
limits. Requests can ask for `Accept: text/event-stream`; the server emits a
standards-compatible SSE `result` event. The server maps malformed input to `400`,
oversized requests to `413`, missing workflows to `404`, and execution failures to
`500` without returning exception internals.

The JDK server is intentionally small. Put TLS termination, rate limiting, identity
providers, and network policy at the application edge for production deployments.

## Spring Boot

Add `lambda-agent-spring-boot-starter` and provide an `Agent` bean. The auto-configuration
registers `/agent/run` and binds:

```yaml
lambda:
  agent:
    bearer-token: ${LAMBDA_AGENT_TOKEN}
    max-request-bytes: 65536
    max-response-bytes: 131072
```

The starter uses normal Spring Security/Actuator integration points when applications
need richer authentication, rate limiting, health checks, or metrics.

## MCP

The optional `lambda-agent-mcp` module connects agents to MCP servers with the official
MCP Java SDK (2.x). The core module stays free of MCP dependencies.

```xml
<dependency>
    <groupId>ai.lambda</groupId>
    <artifactId>lambda-agent-mcp</artifactId>
    <version>${lambda.version}</version>
</dependency>
```

```java
try (McpServer github = McpServer.http("github", "https://example.com/mcp")
        .header("Authorization", "Bearer " + token)
        .requireApprovalForWrites(true)
        .connect()) {
    AgentConfig config = new AgentConfig(prompt, model, github.tools(), 10);
}
```

- `McpServer.stdio(name, command, args...)` starts a local server process (stopped on
  `close()`); `McpServer.http(name, url)` uses Streamable HTTP; `McpServer.transport(...)`
  accepts any SDK transport.
- Tools are named `<server>__<tool>`, using only letters, digits and underscores (at most
  64 characters), so they are valid for every model provider. `prefixToolNames(false)`
  turns the prefix off; `include(...)` exposes only chosen tools.
- Tool hints map to capabilities: read-only tools get `READ`, others `WRITE`; open-world
  tools add `NETWORK`; destructive tools add `SENSITIVE`. Missing hints use the MCP
  defaults, so unannotated tools get `WRITE`, `NETWORK` and `SENSITIVE`. Hints are
  declared by the server, so only trust them as far as you trust the server.
- Results the server marks as errors are returned to the model as
  `MCP tool error: ...` so it can recover, with `details.isError = true`.
- Tools are loaded once at `connect()`; reconnect to pick up tool list changes.

`McpToolAdapter` and `McpToolRegistry` remain available for binding tools by hand.
