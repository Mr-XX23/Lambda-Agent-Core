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

`McpToolAdapter` converts an MCP declaration and invocation callback into an `AgentTool`.
`McpToolRegistry` provides duplicate-safe registration and lookup. Bind it to the MCP
SDK transport used by the host application; the core module does not force a particular
MCP implementation or protocol version.
