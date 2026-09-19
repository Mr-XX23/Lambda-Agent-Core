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
