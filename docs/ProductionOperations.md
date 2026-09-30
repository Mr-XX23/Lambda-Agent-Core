# Production operations

## Session persistence

Run several instances against one database with `DatabaseSessionStore`; see
[Database Sessions](DatabaseSessions.md) for the tables, the NoSQL bridge, and how
concurrent saves of one session are handled.

## Checkpoint persistence

`JdbcCheckpointStore` accepts an application-owned `DataSource`, so Postgres drivers,
pooling, credentials, and TLS remain deployment choices. Call `initializeSchema()` from
a migration/bootstrap phase, not on every request. For managed migrations, use:

```sql
CREATE TABLE lambda_workflow_checkpoints (
  execution_id VARCHAR(255) PRIMARY KEY,
  workflow_name VARCHAR(255) NOT NULL,
  next_step INTEGER NOT NULL,
  status VARCHAR(32) NOT NULL,
  state_json TEXT NOT NULL,
  error_text TEXT,
  version BIGINT NOT NULL
);
```

Use a bounded connection pool and set its acquisition timeout. The JDBC store uses a
transaction and `SELECT ... FOR UPDATE`; callers should retry `OptimisticLockException`
only after reloading the checkpoint and deciding whether the operation is idempotent.
Do not blindly retry a failed workflow step.

`RedisCheckpointStore` is driver-neutral. Bind `RedisCheckpointClient` to a Redis
implementation whose `compareAndSet` uses an atomic transaction or Lua script.
Configure key expiry according to workflow retention requirements; never expire active
checkpoints without an explicit recovery policy.

## Worker coordination

Use unique execution IDs. Multiple workers may load the same checkpoint, but only the
worker with the expected version can commit the next state. A stale worker receives
`OptimisticLockException` and must stop or reload; it must not overwrite newer state.

## Sandbox limits

The prebuilt file, process, and network tools enforce root/host allowlists, approval,
timeouts, and bounded output. These are application-level limits, not OS isolation.
For hostile workloads, run tools in a separate container or service with OS-level CPU,
memory, filesystem, syscall, and egress restrictions.

## Timeouts, retries and downloads

`HttpOptions` sets these for every provider (`new XxxModelClient(key, model, options, baseUrl)`):

- **Connect timeout** (10 s): the longest wait to open a connection.
- **Request timeout** (10 minutes): the longest wait for the model to start answering or, while an
  answer streams in, to send more of it. Reasoning models can think for minutes before the first
  word, so keep it generous. A stream that goes quiet for longer is abandoned with the error
  *"... stopped sending data"* instead of hanging the agent.
- **Call timeout** (`HttpOptions.MAX_CALL`, 1 hour, or the request timeout if longer): the limit
  for one whole call, so long streamed answers are not cut off early but nothing runs forever.
- **Retries** (3 attempts): on 429 and 5xx answers and on connection failures, waiting
  `initialBackoff` and doubling, at most 30 s between attempts; a `Retry-After` longer than the
  request timeout returns the answer instead of waiting. A request that reached the provider but
  timed out is **not** sent again, so a slow image or video generation is not billed twice.

Files a provider points to (generated images and videos given as URLs) are downloaded by
`Downloads`: without the API key (the URL is often a storage or CDN host), over https only, and at
most 512 MB. Error messages name the host, not the full URL, which is often signed.

SDK clients are shared: model clients and generators with the same provider, key and settings use
one SDK client and so one connection pool (`ProviderClients`, at most 64). Creating clients per
request or per subagent is cheap and keeps connections warm.

## Performance

What the framework does for you:

- **Shared connections.** Model clients and generators with the same connect timeout share one
  HTTP/2 client, so requests reuse warm connections (no new TCP/TLS handshake) across clients,
  subagents and parallel tool calls. Claude clients reuse the SDK's own connection pool.
- **Token counts are cached.** `TokenLimitStrategy` counts each message once; in a 20-step
  conversation that is 41 counting calls instead of 440 (each is a network call on Gemini and
  Claude).
- **Media is encoded once.** A `Media` object keeps its base64 form, so images and files in the
  history are not re-encoded on every model call.
- **Claude prompt caching is on by default.** The system prompt gets a cache breakpoint and the
  growing conversation is cached automatically, so each agent step reads the unchanged history
  from cache. Check `cache_read_input_tokens` in Anthropic's usage reports to confirm hits; turn it
  off with `withPromptCaching(false)` where automatic caching is not available.

What to choose yourself:

- **Mark read-only tools as parallel-safe.** When a model asks for several tools in one reply,
  the ones that return `true` from `AgentTool.isParallelSafe()` run at the same time, so ten web
  lookups take as long as the slowest one instead of the sum. Every other tool waits for the
  tools before it and runs alone, so a write never overlaps anything. For
  `search, fetch, fetch, write_file, fetch` the first three run together, then the write, then
  the last fetch. Permission checks and approvals still happen in order, and results keep the
  model's order.
  - Return `true` only for tools that read or look things up and share no state between calls.
    Tools that write files, change records or update session data keep the default, `false`.
  - Built in: `FileReadTool`, `NetworkFetchTool`, the skill tools, and MCP tools the server marks
    read-only are parallel-safe. `FileWriteTool`, `ProcessTool` and `invoke_subagent` are not.
  - `withMaxParallelTools(n)` (default 8) caps how many run at once, to stay within the rate
    limits of the sites and APIs your tools call.
  - `withToolParallelism(ToolParallelism.NEVER)` runs everything one by one;
    `ToolParallelism.ALWAYS` (or `withParallelToolCalls(true)`) runs every tool together, for
    agents whose tools are all independent.
- **Keep histories append-only for cache hits.** Trimming strategies change the start of the
  history, which ends provider-side prompt caching from that point; prefer a large enough budget
  that trimming is rare.
- **Create a model client once** and reuse it; `withCapabilities(...)` and similar copies share the
  same connections.
