# Lambda AI architecture

## Modules

The Maven reactor is split into two libraries and a set of executable examples:

- `lambda-ai-core` contains provider-neutral conversation types and the `ModelClient` abstraction.
- `lambda-agent-core` contains the agent loop, tool API, event listeners, and session stores.
- `examples` contains small CLI applications for chat, streaming, tool calling, JSONL persistence, and a stateful todo agent.

The libraries use Java 25, Maven, the JDK HTTP client, and `org.json`. No web framework is required.

## Request flow

`Agent.run(sessionId, input)` loads a session, inserts the system and user messages, and calls `ModelClient.streamChat`. Each response is appended to the session. If the response contains tool calls, the agent resolves each name through its configured `AgentTool` registry, executes it with a `ToolInvocationContext`, appends the result as a `TOOL` message, and repeats until the model returns text or the iteration limit is reached.

`AgentEventListener` receives iteration, assistant, streaming-delta, and tool lifecycle events. This keeps console output, UI streaming, and telemetry outside the orchestration core.

## Providers

`GoogleModelClient` maps the internal message model to Gemini contents and parses Gemini SSE responses. `OpenAIModelClient` maps messages and function schemas to the Chat Completions API, parses function calls, and uses a synchronous request as a compatibility fallback for `streamChat`.

Provider clients are deliberately small adapters. Applications can implement `ModelClient` for another provider or for deterministic tests.

## Sessions and persistence

`InMemorySessionStore` is intended for short-lived applications and tests. `JsonlSessionStore` stores one JSON message per line and a separate metadata JSON file. Writes use a temporary file, flush and force the file contents, then replace the target so an interrupted write does not normally leave a partial session. Session IDs are restricted to filename-safe characters to prevent path traversal.

Session messages and metadata are mutable by design so tools can maintain application state. Applications that share a session across concurrent requests should serialize calls to `Agent.run` per session.

Each run receives a unique run ID. `CancellationToken` can stop a run between model/tool steps, and `AgentConfig` supports a run deadline and maximum tool-argument size. `AgentEventListener` exposes run start/end and normalized model-response events for tracing.

Model calls can use a bounded `RetryPolicy` with fixed or exponential backoff. Retries are cancellation-aware and cannot extend the run deadline; `onModelRetry` exposes each retry for tracing and metrics. The default policy is `RetryPolicy.none()` so applications opt into retry behavior explicitly.

## Durable workflows

`Workflow` provides explicit sequential execution over `WorkflowStep` functions. A `CheckpointStore` persists the next step and a serializable state map after every successful step. If a step fails, the checkpoint records the failed step and state; rerunning with the same execution ID resumes at that step rather than repeating earlier work. Completed execution IDs are idempotent. `InMemoryCheckpointStore` is provided for tests and short-lived processes. `JsonlCheckpointStore` provides an atomic JSON-backed implementation for local durable execution; database-backed stores can implement the same interface.

## Observability

`AgentTracer` extends the event listener API with a structured `TraceEvent` stream. `InMemoryAgentTracer` captures run, iteration, model, retry, and tool lifecycle events, including run IDs, session IDs, token usage, finish reasons, error types, and durations. Applications can implement `AgentTracer` to export the same events to OpenTelemetry, logs, or a metrics backend without coupling the agent runtime to a vendor.

## Workflow and tool safety

`WorkflowStepSpec` adds optional conditions, per-step retry policies, and timeouts to sequential workflows. Existing `WorkflowStep` constructors remain supported. Tools can override `AgentTool.getPolicy()` to require approval and set an execution timeout; `AgentConfig` supplies the approval handler. Unapproved calls become explicit tool messages and are not executed.

## Error handling

Tool failures use `ToolErrorStrategy.SEND_TO_MODEL` by default, adding an error `TOOL` message so the model can recover. `THROW` aborts immediately. Provider and persistence failures are surfaced as runtime exceptions; callers should log them and decide whether to retry.
