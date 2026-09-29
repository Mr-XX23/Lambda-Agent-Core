# Production examples

## Parallel workflow with approval

```java
WorkflowStep fanOut = Workflow.parallel("research", List.of(
    new WorkflowStepSpec("catalog", (ctx, token) -> ctx.put("catalog", loadCatalog())),
    new WorkflowStepSpec("pricing", (ctx, token) -> ctx.put("pricing", loadPricing()))
));
Workflow workflow = new Workflow("order", List.of(
    fanOut,
    new ApprovalWorkflowStep("submit",
        (executionId, step, context) -> approvalService.isApproved(executionId)),
    (ctx, token) -> submit(ctx.getState())
), new JdbcCheckpointStore(dataSource));
WorkflowResult result = workflow.run(executionId, Map.of(), new CancellationToken());
```

An unapproved run returns `WAITING_APPROVAL`; calling `run` again with the same
execution ID resumes at the approval step.

## Permission and typed input

```java
AgentTool tool = new FileWriteTool(workspace);
ToolPermissionPolicy policy = ToolPolicyComposition.allOf(List.of(
    (session, candidate, capabilities) -> capabilities.contains(ToolCapability.WRITE)
        ? true : false,
    (session, candidate, capabilities) -> session.startsWith("trusted-")
));
```

Configure the policy in `AgentConfig`, attach an `AgentEventListener` to record
`ToolAuditEvent`, and use `ProcessTool`/`NetworkFetchTool` only with explicit
allowlists.

## Replay and evaluation

```java
List<TraceEvent> trace = tracer.snapshot();
new TraceReplay().replay(trace, new InMemoryAgentTracer());
List<EvaluationResult> results = new AgentEvaluator().evaluate(agent, cases);
```

Evaluation cases should be committed as regression fixtures and run in CI with
provider fakes rather than live credentials.

## Persistence and HTTP

Use `RedisCheckpointStore` with an application-provided atomic Redis client for
distributed workers, or `JdbcCheckpointStore` with a pooled `DataSource`. Expose an
agent through `AgentHttpServer` with a bearer token and request limits, or add the
Spring Boot starter and provide an `Agent` bean.

## Skills

```java
Skills skills = Skills.load(Path.of("skills"));        // one skill per subfolder with SKILL.md
AgentConfig config = new AgentConfig(prompt, model, tools, 8)
    .withSkills(skills)                                 // adds load_skill and read_skill_file
    .withContextStrategy(new SlidingWindowStrategy(40));
```

Each `SKILL.md` starts with frontmatter holding `name` (lowercase letters, digits,
hyphens; at most 64 characters) and `description` (what the skill does and when to use
it; at most 1024 characters), followed by the instructions. Only names and descriptions
are placed in the system prompt, so many skills cost little context until used. Both
skill tools declare `ToolCapability.READ`, so a `ToolPermissionPolicy` that denies reads
also blocks skills. The system prompt is stored when a session starts, so existing
sessions keep the skills they started with. Runnable example: `examples/skills-agent`.

## Subagents

```java
Subagent reviewer = new Subagent("code-reviewer",
        "Finds bugs in the Java files it is given. Give it exact file paths.",
        "You are a careful senior Java reviewer. ...",
        List.of("read_file"));                           // must also be one of the agent's tools

AgentConfig config = new AgentConfig(prompt, model, List.of(new FileReadTool(workspace)), 10)
    .withSubagents(Subagents.of(reviewer).withSelfCloning(true));
```

The agent gets an `invoke_subagent` tool taking `{"tasks": [{"agent": "...", "task": "..."}]}`.
Tasks in one call run in parallel (`withMaxParallel`, default 4; `withMaxTasksPerCall`,
default 8), each in a fresh subagent that sees only its task text. Results come back as
one tool result, one section per task; a failed task is reported as `FAILED: <reason>`
without stopping the others.

- Specialist subagents get only their listed tools and cannot delegate further.
- Self-clones (`self`) get the agent's instructions and tools, and can delegate again
  until `withMaxDepth` (default 2, at most 10) is reached.
- Subagent session ids start with the parent's session id (`<parent>:<agent>-<n>-<id>`),
  so permission policies that look at session ids apply to subagents too.
- Subagent events may arrive from several threads at once; listeners must be thread-safe.

Definition files go in one folder: `<name>.md` or `<name>/agent.md`, with `name`,
`description`, optional `tools` (a list) and optional `model` (a key of the map passed to
`Subagents.load(dir, models)`, or `inherit`). Runnable example: `examples/multi-agent`.

## Structured output

```java
record Verdict(@Description("spam or ham") Label label, double confidence, List<String> reasons) {}

StructuredResult<Verdict> result = agent.run(sessionId, "Classify:\n" + email,
        StructuredOutput.of(Verdict.class)
            .withValidator(v -> {
                if (v.confidence() < 0 || v.confidence() > 1) throw new IllegalArgumentException("confidence must be 0..1");
            }));
Verdict verdict = result.value();
String json = result.run().getFinalText();   // the same answer as JSON
```

- The agent gets a `submit_result` tool whose parameters are the answer's schema. A plain-text
  reply is answered with a reminder to call it; both count toward the iteration limit.
- Rejected answers come back to the model as a list of problems with JSON paths. Record
  components are required unless they are `Optional`; unknown fields are rejected; numbers
  and booleans sent as strings (`"42"`, `"true"`) and enum names in any case are accepted.
- Validators signal problems with `IllegalArgumentException`; so do records' compact
  constructors. Other exceptions are treated as bugs and end the run.
- If no valid answer arrives within the iteration limit, `StructuredOutputException` is
  thrown with the last problems; the session is saved first.
- `StructuredOutput` also implements `TypedToolInput`, so a tool can use
  `StructuredOutput.of(Args.class)` for its schema (`schemaJson()`) and argument checking.
- Supported types: records, `String`, `char`, integer and decimal numbers (including
  `BigInteger`/`BigDecimal`), `boolean`, enums, `List`, `Set`, arrays, `Map<String, V>`,
  `Optional`, `LocalDate`, `LocalTime`, `LocalDateTime`, `Instant`, `OffsetDateTime`, `UUID`,
  `URI`. Records that contain themselves are not supported.
