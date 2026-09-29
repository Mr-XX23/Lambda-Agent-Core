# Project Goals and Scope

## Build a Java library (not an app)
The goal is to build a Java library that plays the same role as **pi-ai + pi-agent-core**.

### Core Responsibilities
* One clean LLM API abstraction
* A generic agent loop that can call tools
* Pluggable storage and transports

### Primary Goals
* Minimal, understandable core
* Easy to embed in Spring Boot or other Java apps
* Ready to publish as OSS on GitHub and Maven Central later

### Non‑Goals (v1)
* No UI / TUI
* No multi-agent orchestration
* No advanced planning trees (simple loop only)

### Naming
**groupId:** `ai.lambda`

Artifacts:
* `lambda-ai-core` — LLM abstraction
* `lambda-agent-core` — agent loop, tools, sessions

---

## High Level Architecture
Two layers similar to Pi, but implemented in Java.

### lambda-ai-core
#### Responsibilities
* Abstract over different LLM providers.
* Provide a **ModelClient interface**.

Example API:

``` ChatResponse chat(List<Message> messages, List<ToolSchema> tools)```

Streaming support can be added later.

#### Core Types
* `Message`
* `Role`
* `ToolSchema`
* `ToolCall`

---

### lambda-agent-core

#### Responsibilities

* Implement the agent execution loop: prepare context, call model, inspect tool calls, execute tools, loop until done.
* Define AgentTool abstraction and a tool registry.
* Call the model
* Manage ``` AgentSession state ``` (messages + custom metadata).
* Provide extensibility points: events/listeners, pluggable session store.

Additional responsibilities:

* Define `AgentTool` abstraction
* Provide tool registry
* Manage `AgentSession` state
* Provide extensibility hooks

Extensibility examples:
* Event listeners
* Pluggable session store

All other interfaces (HTTP, CLI, etc.) should live in **separate modules later**.

---

# Core Concepts and Java Types

## Shared Types (lambda-ai-core)

### Role

``` enum Role { SYSTEM, USER, ASSISTANT, TOOL } ```

### Message
```
final class Message

Role role
String content
String toolCallId
```

Notes:
* `content` starts as a simple string , multi-part content can be added later
* `toolCallId` is only used for TOOL messages

---

### ToolSchema

```
final class ToolSchema

String name
String description
String jsonSchema
```

---

### ToolCall

```
final class ToolCall

String id
String name
String argumentsJson
```

---

### ModelClient

```
interface ModelClient

ChatResponse chat(List<Message> messages, List<ToolSchema> tools)
```

---

### ChatResponse

```
final class ChatResponse

Message assistantMessage
List<ToolCall> toolCalls
```

---

# Agent Side (lambda-agent-core)

## AgentConfig

```
final class AgentConfig

String systemPrompt
ModelClient modelClient
List<AgentTool> tools
int maxIterations
```

`maxIterations` acts as a safety stop.

---

## AgentTool

```
interface AgentTool

String getName()
String getDescription()
String getJsonSchema()

ToolResult execute(ToolInvocationContext ctx) throws Exception
```

---

## ToolInvocationContext

```
final class ToolInvocationContext

String toolCallId
String argumentsJson
AgentSession session
CancellationToken cancellation
```

`CancellationToken` can be implemented later.

---

## ToolResult

```
final class ToolResult

String content
Map<String,Object> details
```

`details` is optional and useful for logging.

---

## AgentSession

```
final class AgentSession

String id
List<Message> messages
Map<String,Object> metadata
```

---

## SessionStore

```
interface SessionStore

AgentSession load(String id)
void save(AgentSession session)
```

---

## AgentEventListener

Used for streaming and runtime events.

Examples:

* `onAssistantDelta(...)`
* `onToolStart(...)`
* `onToolEnd(...)`

---

## Agent

```
final class Agent

AgentConfig config
SessionStore sessionStore
List<AgentEventListener> listeners
```

### Core Method

```
AgentResult run(String sessionId, String userInput)
```

---

## AgentResult

```
final class AgentResult

String finalText
AgentSession session
```

---

# Agent Loop Design

Below is the core logic for `Agent.run()`.

```
public AgentResult run(String sessionId, String userInput) {

  AgentSession session = sessionStore.loadOrCreate(sessionId);

  if (session.getMessages().isEmpty()) {
      session.getMessages().add(
        new Message(Role.SYSTEM, config.getSystemPrompt(), null)
      );
  }

  session.getMessages().add(
    new Message(Role.USER, userInput, null)
  );

  for (int i = 0; i < config.getMaxIterations(); i++) {

      ChatResponse response = config.getModelClient()
          .chat(session.getMessages(), toolSchemas());

      Message assistant = response.getAssistantMessage();
      session.getMessages().add(assistant);

      notifyAssistantMessage(assistant);

      List<ToolCall> toolCalls = response.getToolCalls();

      if (toolCalls == null || toolCalls.isEmpty()) {
          sessionStore.save(session);

          return new AgentResult(
            assistant.getContent(),
            session
          );
      }

      for (ToolCall call : toolCalls) {

          AgentTool tool = toolRegistry.get(call.getName());

          ToolInvocationContext ctx = new ToolInvocationContext(
              call.getId(),
              call.getArgumentsJson(),
              session,
              CancellationToken.none()
          );

          notifyToolStart(call, ctx);

          ToolResult result = tool.execute(ctx);

          notifyToolEnd(call, result);

          Message toolMessage = new Message(
              Role.TOOL,
              result.getContent(),
              call.getId()
          );

          session.getMessages().add(toolMessage);
      }
  }

  sessionStore.save(session);

  return new AgentResult(
    "[lambda-agent-core] Stopped after max iterations.",
    session
  );
}
```

This loop is essentially the **Java version of the Pi agent loop**.

---

# Phased Implementation Plan

This section can be copied into `plan.md`.

---

# Phase 1 — Skeleton and Simple Agent

### Goal

Create `lambda-ai-core` and a basic `Agent` that simply calls an LLM.

### Steps

#### 1. Project Setup

Create a multi-module Maven or Gradle project.

Structure:

```
lambda-agent-core-java/

modules

lambda-ai-core/
lambda-agent-core/
examples/
```

---

#### 2. Implement lambda-ai-core

Create:

* `Message`
* `Role`
* `ChatResponse`
* `ModelClient`
* `ToolSchema`
* `ToolCall`

Initially tools can remain unused.

Implement:

```
OpenAIModelClient
```

Responsibilities:

* Call the chat API
* Accept tool schemas
* Tools can be ignored in the first version

---

#### 3. Implement lambda-agent-core

Create:

* `AgentSession`
* `SessionStore`

Implement:

* In-memory store
* Simple file-based store

Create:

* `AgentConfig`
* `Agent`

Initial `run()` behavior:

* Add system message
* Add user message
* Call `ModelClient.chat()`
* Return assistant response

No loops or tools yet.

---

#### 4. Example Module

Create `examples/simple-chat`.

Example flow:

```
OpenAIModelClient client = ...

AgentConfig config = ...

Agent agent = new Agent(config);

agent.run("session-1", "Hello, who are you?");
```

### Deliverable

**v0.1 — Simple Chat Agent**

---

# Phase 2 — Tool System and Full Loop

### Goal

Implement the full agent loop with tool calling.

### Steps

Create these interfaces in `lambda-agent-core`:

* `AgentTool`
* `ToolInvocationContext`
* `ToolResult`

Extend:

* `ModelClient.chat()`
* `ChatResponse`

To support tool calling aligned with provider schemas.

---

### Implement

Inside `Agent`:

* Tool registry
* `toolSchemas()` converter

Update the agent loop:

1. Send tool schemas to the model
2. If no tool calls → finish
3. If tool calls exist:

    * Execute tool
    * Append TOOL message
    * Loop again

---

### Example

Create `examples/tools-weather`.

Example tool:

```
WeatherTool
```

Flow:

```
User Question
  ↓
Model Requests WeatherTool
  ↓
WeatherTool Executes
  ↓
Tool Result Returned
  ↓
Model Generates Final Answer
```

### Deliverable

**v0.2 — Agent With Tools**

---

# Phase 3 — Sessions and Persistence

### Goal

Make the system production-friendly.

### Improvements

#### SessionStore

Add:

* File-based JSONL storage

Pattern:

* Append messages per session

Also allow future implementations:

* Database stores

---

#### Agent Events

Add interface:

```
AgentEventListener
```

Possible events:

* `onAssistantMessage`
* `onToolStart`
* `onToolEnd`
* `onIteration`

---

#### Configuration Options

Add configuration parameters:

* `maxIterations`
* `toolErrorStrategy`

Example strategies:

* Throw exception
* Send error text back to the model

---

### Example Project

Create:

```
examples/todo-agent
```

Capabilities:

* Add todo
* List todos

Storage:

* Session metadata
* File storage

### Deliverable

**v0.3 — Stateful Agent Core**

---

# Phase 4 — Polishing and Publishing

### API Review

Goals:

* Clean naming
* Java-style conventions
* Minimal public surface

---

### Documentation

Add:

```
README.md
```

Contents:

* Overview
* Architecture diagram
* Quick start guide

Create `docs/` directory.

Documentation topics:

* Concepts: ModelClient, Agent, Tools, Session
* How-to guides

Examples:

* Create a tool
* Integrate with Spring Boot

---

### CI and Publishing

Add:

* Tests
* GitHub Actions CI

Prepare for **Maven Central publishing**:

* groupId
* license
* artifact metadata

---

### Spring Boot Example

Create module:

```
examples/spring-agent-api
```

Expose a REST endpoint that calls:

```
Agent.run()
```

---

### Deliverable

**v1.0 — Public Open Source Release**
