# Software Delivery Agent

The `software-delivery-agent` example demonstrates a safe first agent built on
Lambda AI. It is deliberately a **review and verification agent**, not an
unrestricted coding bot.

## Run it

From the repository root:

```bash
set GEMINI_API_KEY=your-key
mvn -pl examples/software-delivery-agent -am install -DskipTests
mvn -pl examples/software-delivery-agent exec:java
```

The `install` step is required the first time because the example depends on
the sibling `lambda-ai-core` and `lambda-agent-core` modules. It places those
reactor artifacts in the local Maven repository so the separate `exec:java`
invocation can resolve them.

The optional `APPROVE_TOOL_CALLS=true` enables approval-gated model tool calls.
`APPROVE_WORKFLOW=true` resumes the workflow past its human approval step.
Without the latter, the workflow persists a `WAITING_APPROVAL` checkpoint.

## Extension path

Add a typed patch tool, a diff tool, and a pull-request tool only after the
read-only flow is reliable. Keep writes behind `ToolApprovalHandler`, require
the `WRITE` capability, and persist workflow checkpoints in
`JdbcCheckpointStore` or `RedisCheckpointStore` for multi-worker deployments.
