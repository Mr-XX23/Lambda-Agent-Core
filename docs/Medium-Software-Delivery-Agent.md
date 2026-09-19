# Building a Guarded Software Delivery Agent in Java

Software agents become genuinely useful when they can do more than answer
questions. A delivery agent can inspect a repository, understand a request,
run verification, propose a change, and prepare a handoff for a developer.

That power also creates a safety problem: a model should not receive
unrestricted access to the filesystem, shell, network, or source-control
credentials. The right design is not “give the model a terminal.” It is a
durable workflow with explicit tools, bounded permissions, observable
execution, and human approval at the points where consequences become real.

This article shows how to build that first milestone with the Lambda AI Agent
Framework:

**GitHub repository:** https://github.com/Mr-XX23/Lambda-Agent-Core

The repository includes a runnable
[`software-delivery-agent`](https://github.com/Mr-XX23/Lambda-Agent-Core/tree/main/examples/software-delivery-agent)
example. It is intentionally a review and verification agent before it is a
fully autonomous coding bot.

## The goal: useful autonomy without blind trust

A production-oriented delivery agent should separate reasoning from authority.
The model can suggest what to inspect and which checks are useful, but the
framework decides what the model is allowed to execute.

Our initial workflow has four stages:

```text
Repository baseline
        |
        v
Parallel verification
   |              |
 Git state      Build/tests
        \      /
          Join
           |
           v
    Human approval
           |
           v
         Handoff
```

This structure gives us several important properties:

- **Read-only by default:** the first version can inspect files and run
  explicitly allowlisted checks, but cannot silently modify the repository.
- **Parallel verification:** independent checks run as separate workflow
  branches.
- **Durable progress:** checkpoints allow a workflow to pause for approval and
  resume later.
- **Auditable decisions:** tools, approvals, retries, and workflow transitions
  are framework-level events rather than hidden model behavior.

## Starting the example

The example uses Java 25 and Maven. Set a Gemini key and compile the example
from the repository root:

```bash
export GEMINI_API_KEY="your-key"
mvn -pl examples/software-delivery-agent -am compile
mvn -pl examples/software-delivery-agent exec:java
```

On Windows PowerShell, use:

```powershell
$env:GEMINI_API_KEY = "your-key"
mvn -pl examples/software-delivery-agent -am compile
mvn -pl examples/software-delivery-agent exec:java
```

The application accepts an optional repository path:

```bash
mvn -pl examples/software-delivery-agent exec:java \
  -Dexec.args="/path/to/repository"
```

The agent starts with a repository inspection workflow and then opens a small
interactive session for repository questions.

## Configuring the model and tools

The model is connected through the framework’s provider abstraction, so the
agent loop does not need to know whether the provider is Gemini or OpenAI.
Tools are registered explicitly:

```java
var model = new GoogleModelClient(
        apiKey,
        "gemini-3.1-flash-lite-preview"
);

var tools = List.<AgentTool>of(
        new FileReadTool(repository),
        new ProcessTool(Set.of(
                "mvn -q test",
                "mvn -q verify",
                "git diff --check"
        ))
);
```

Two details are important here.

First, `FileReadTool` is rooted at the selected repository and uses
symlink-safe path resolution. A request for `../../secrets.txt` or a symlink
that escapes the repository is rejected.

Second, `ProcessTool` does not accept arbitrary shell commands. It receives an
exact allowlist. It also drains process output concurrently, applies output
limits, and enforces a timeout. These controls protect both security and
availability.

The model receives tool schemas, but the tool implementation remains the
authority. A model-generated JSON argument is never a permission grant.

## Giving the agent a controlled role

The system prompt establishes the agent’s role:

```java
var config = new AgentConfig(
        """
        You are a senior software-delivery agent. Inspect the repository before
        making recommendations. Use file reads and allowlisted checks only.
        Never claim that code changed unless a write operation was explicitly
        approved by a human. Explain risks, tests, and rollback considerations.
        """,
        model,
        tools,
        8,
        ToolErrorStrategy.SEND_TO_MODEL,
        Duration.ofMinutes(5),
        64 * 1024,
        RetryPolicy.none(),
        (sessionId, call) ->
                "true".equalsIgnoreCase(
                        System.getenv("APPROVE_TOOL_CALLS")
                ),
        ToolPermissionPolicy.allowAll()
);
```

The prompt improves behavior, but it is not the security boundary. The actual
boundary is the combination of tool schemas, capability policies, approval
handlers, path restrictions, command allowlists, deadlines, and result limits.

That distinction matters. Prompts describe intent; policies enforce authority.

## Building a durable workflow

The workflow uses explicit steps and an in-memory checkpoint store for the
example:

```java
var workflow = new Workflow(
        "software-delivery-review",
        List.of(
                new WorkflowStepSpec(
                        "repository-baseline",
                        (context, token) -> inspect(repository, context)
                ),
                new WorkflowStepSpec(
                        "parallel-verification",
                        Workflow.parallel(
                                "verification",
                                List.of(
                                        new WorkflowStepSpec(
                                                "git-state",
                                                (ctx, token) -> ctx.put(
                                                        "gitState",
                                                        "inspect with git diff --check"
                                                )
                                        ),
                                        new WorkflowStepSpec(
                                                "build-state",
                                                (ctx, token) -> ctx.put(
                                                        "buildState",
                                                        "run mvn -q test"
                                                )
                                        )
                                )
                        )
                ),
                new WorkflowStepSpec(
                        "approval",
                        new ApprovalWorkflowStep(
                                "approve-write-capable-actions",
                                approvalHandler
                        )
                )
        ),
        new InMemoryCheckpointStore(),
        true
);
```

The parallel step is useful because repository analysis and verification often
have no dependency on each other. The workflow engine joins the branches
before moving to approval.

For a real deployment, replace the in-memory store with
`JdbcCheckpointStore` backed by Postgres or `RedisCheckpointStore` backed by an
atomic Redis client. That makes approval pauses and worker restarts survivable.

## Human approval is a workflow state, not a prompt

The approval step is intentionally explicit:

```java
new ApprovalWorkflowStep(
        "approve-write-capable-actions",
        (executionId, stepName, context) ->
                "true".equalsIgnoreCase(
                        System.getenv("APPROVE_WORKFLOW")
                )
)
```

When approval is not available, the workflow returns a `WAITING_APPROVAL`
status and persists the checkpoint. A separate operator or UI can approve the
execution and resume it later.

This is safer than asking the model, “Are you sure?” The model cannot approve
its own authority. Approval belongs to a human or an external policy service.

In a production version, the same step could call an access-control service,
create a ticket, or publish an approval request to a dashboard. The workflow
contract remains the same.

## The path from reviewer to coding agent

The read-only implementation is a deliberate first milestone. The next tools
should be added in this order:

1. **Diff tool** — show proposed changes without applying them.
2. **Typed patch tool** — apply a validated patch only inside the repository
   root.
3. **Test and lint tools** — expose structured results, not unbounded text.
4. **Source-control tool** — create a branch and commit only after approval.
5. **Pull-request tool** — open a pull request with test and security evidence.

Each write-capable tool should require the `WRITE` capability and an approval
policy. It should also emit an audit event containing the actor, execution ID,
tool name, decision, and denial reason when applicable.

The agent should never receive a GitHub token simply because it can run a
process. Credentials should be injected into narrowly scoped integrations and
kept outside model-visible tool arguments.

## Observability and evaluation

An agent that changes code needs stronger evaluation than a chatbot. Track:

- task completion rate;
- tests passed before and after a change;
- incorrect tool-call rate;
- approval denial rate;
- token and model cost;
- workflow latency;
- retry and timeout frequency;
- files changed outside the requested scope.

Lambda AI provides trace events, replay hooks, metrics, and OTLP export
boundaries for this purpose. A useful evaluation dataset contains realistic
repository tasks with expected files, required tests, forbidden paths, and
acceptable outcomes.

Replay is especially valuable after a failure. You should be able to inspect
the exact model response, tool arguments, policy decision, checkpoint, and
retry sequence that led to the result.

## Security checklist

Before enabling write operations, verify that:

- file reads and writes resolve symlinks safely;
- paths remain inside the configured workspace;
- processes are exact-command allowlisted;
- stdout and stderr are drained concurrently;
- process output and runtime are bounded;
- network access is HTTPS-only and host-allowlisted;
- response bodies are streamed with hard size limits;
- HTTP requests have authentication, concurrency, and size limits;
- approval requests persist across restarts;
- checkpoint writes use optimistic locking;
- every denial is observable and explains why it happened;
- credentials never appear in prompts, traces, or tool results.

The most dangerous design is an agent that appears autonomous but has
unreviewed authority. Bounded tools and explicit workflow state make autonomy
composable instead of mysterious.

## Conclusion

A software delivery agent does not need to begin as an autonomous programmer.
It can begin as a disciplined repository analyst that gathers evidence,
executes safe checks, and pauses before consequential actions.

That first version already provides value to developers while creating the
correct foundation for more capable behavior: typed tools, parallel workflows,
durable checkpoints, human approval, trace replay, and production policy
integration.

Explore the complete implementation here:

**https://github.com/Mr-XX23/Lambda-Agent-Core**

