# Production operations

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

- **Parallel tool calls** (`AgentConfig.withParallelToolCalls(true)`): when a model asks for
  several tools at once, they run at the same time and the step takes as long as the slowest tool
  instead of the sum. Enable it only if your tools are safe to run concurrently; permission checks
  and approvals still run in order, and results keep the model's order.
- **Keep histories append-only for cache hits.** Trimming strategies change the start of the
  history, which ends provider-side prompt caching from that point; prefer a large enough budget
  that trimming is rare.
- **Create a model client once** and reuse it; `withCapabilities(...)` and similar copies share the
  same connections.
