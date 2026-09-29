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
