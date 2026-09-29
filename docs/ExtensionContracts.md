# Extension contracts and compatibility

## Public contracts

`ModelClient` is the provider boundary. Implementations must return a `ChatResponse`
with an assistant message, normalized `FinishReason`, usage metadata, and tool calls.
`streamChat` should invoke its delta callback in order and must surface transport and
malformed-response failures.

`AgentTool` is the tool boundary. Names and JSON schemas are stable model-facing
identifiers. Implementations may add `ToolPolicy`, `TypedToolInput`, capabilities, and
argument validation. Authorization is evaluated before validation and execution.

`CheckpointStore` is the workflow persistence boundary. Implementations must preserve
execution IDs and versions and must reject stale expected-version writes with
`OptimisticLockException`.

`ModelClient.countTokens` has a default estimate of about four characters per token;
clients whose provider offers a token-counting API should override it, since
`TokenLimitStrategy` relies on it.

`ContextStrategy` is the history-trimming boundary. `optimize` returns the messages
sent to the model and must not modify the session. Results must keep each tool call
together with its results and start (after the system prompt) with a user message;
`SlidingWindowStrategy` and `TokenLimitStrategy` guarantee this.

`SKILL.md` is the skill format boundary: YAML frontmatter with `name` (lowercase
letters, digits and hyphens, at most 64 characters) and `description` (at most 1024
characters), followed by Markdown instructions. The `load_skill` and
`read_skill_file` tool names are stable model-facing identifiers.

`AgentEventListener` and `AgentTracer` are lifecycle boundaries. New callbacks are
default methods where possible so existing implementations remain source-compatible.

## Compatibility policy

The project follows semantic versioning after the first stable release:

- Patch releases preserve behavior and public source/binary compatibility.
- Minor releases may add types, methods, default callbacks, and optional modules.
- Major releases may remove or change public contracts.

The `0.x` line may still make breaking changes, but deprecated APIs remain available
for at least one minor release when practical. Applications should pin the framework
and provider versions together and run the compatibility CI job before upgrades.
