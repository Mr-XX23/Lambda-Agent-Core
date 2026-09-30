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

## Cancellation, streaming and shared tool state

Cancel a run from another thread. Tools still running are interrupted at once, tools not yet
started are skipped, and each of the reply's tool calls is recorded as cancelled, so the saved
session can be continued. Long-running tools can also check the token and stop cleanly:

```java
CancellationToken token = new CancellationToken();
executor.submit(() -> agent.run("session", "Index the repository", token));
token.cancel();   // later, for example when the user closes the page

// inside a tool
public ToolResult execute(ToolInvocationContext context) throws Exception {
    for (Path file : files) {
        context.getCancellationToken().throwIfCancelled();
        index(file);
    }
    return ToolResult.of("indexed " + files.size() + " files");
}
```

Cancelling a run also stops its subagents. `token.child()` gives part of the work its own token:
it is cancelled with its parent, and can be cancelled alone without stopping the parent.

`config.withStreaming(false)` asks the model for each reply in one piece instead of streaming it,
for batch jobs or for models and gateways without streaming. Listeners then get no
`onAssistantDelta` calls.

Tools run at the same time only when they declare it, so lookups overlap and writes do not:

```java
final class SearchTool implements AgentTool {
    @Override public boolean isParallelSafe() { return true; }   // only reads: may run alongside others
    // getName, getDescription, getJsonSchema, execute ...
}
```

See [Production Operations](ProductionOperations.md#performance) for the rules and settings.

`session.getMetadata()` is safe to use from tools running in parallel. To change a value based on its current one, use `merge` or
`compute` so two tools cannot overwrite each other:

```java
context.getSession().getMetadata().merge("filesIndexed", 1, (a, b) -> (Integer) a + (Integer) b);
```

Keys and values must not be null; remove a key instead of storing null.

## Persistence and HTTP

Keep sessions in your own database with `DatabaseSessionStore`: `JdbcSessionDatabase` for
any JDBC `DataSource`, or a two-method `SessionDatabase` bridge for NoSQL. See
[Database Sessions](DatabaseSessions.md) and `examples/database-sessions` (H2, Postgres,
MongoDB, Redis).

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

## Providers, media and generation

```java
ModelClient model = OpenAICompatibleModelClient.openRouter(key, "google/gemini-3.8-flash")
        .withCapabilities(ModelCapabilities.of(Modality.IMAGE, Modality.VIDEO).withMediaUrls(Modality.VIDEO));
agent.run("s1", Message.user("What happens in this clip?", Media.fromUrl("https://example.com/clip.mp4")));
```

- `Media.fromFile(path)` detects the type from the extension; `Media.fromUrl(url)` lets the
  provider fetch it where supported (`capabilities().mediaUrls()`), otherwise load the bytes.
- `JsonlSessionStore` keeps media as files next to the session (`<id>.media/<sha256>.<ext>`),
  so the JSONL stays small, identical files are stored once, and saves do not rewrite them.
  Media is still sent to the model on every call; context strategies count text only.
- Plain-text documents (`text/*`) are sent as text to OpenAI-compatible providers and as text
  documents to Claude.
- `OpenAICompatibleProvider.custom(name, baseUrl)` connects any other Chat Completions server
  (vLLM, LM Studio, a gateway); `withHeaders(...)` adds headers such as OpenRouter's
  `HTTP-Referer`.
- Generation: `ImageGenerator` (`OpenAIImageGenerator`, `GeminiMedia.images`,
  `OpenRouterImageGenerator`), `SpeechGenerator` (`OpenAISpeechGenerator`, `GeminiMedia.speech`,
  `XaiMedia.speech`, `MistralMedia.speech`), `VideoGenerator` (`GeminiMedia.videos` for Veo,
  `XaiMedia.videos`; both wait for the background job, up to `VideoRequest.timeout()`), and
  `Transcriber` (`OpenAITranscriber`, `XaiMedia.transcriber`, `MistralMedia.transcriber`).
  OpenAI's video API (Sora) and Google's Imagen were shut down by their providers, so they are not
  offered.
- `MediaTools.generateImage/generateSpeech/generateVideo(generator, outputDir)` and
  `MediaTools.transcribeAudio(transcriber, workspace)` expose these as agent tools; generated files
  are saved to `outputDir`, and transcription only reads files inside `workspace`.
- Claude (`lambda-ai-core-anthropic`) stores each assistant turn, including signed thinking blocks, as
  `ProviderState` and replays it unchanged. It asks the API to drop, rather than reject, thinking
  blocks whose conversation changed (`withMismatchedThinkingDropped(false)` to fail instead), and
  enables server-side refusal fallbacks on the models that support them
  (`withRefusalFallbacks(false)` to turn off). History trimming removes provider state from the
  turns it keeps.
