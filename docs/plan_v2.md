# Lambda Agent Core — v2 Implementation Plan

This document outlines the detailed architectural changes and implementation steps required to upgrade Lambda AI from a synchronous, text-only agent to a production-grade, multi-modal, highly concurrent AI framework.

---

## 1. Streaming Responses
**Goal:** Stream the LLM's text generation back to the UI token-by-token for a responsive user experience.

### Architecture Changes
1. **`ModelClient` Interface Update:**
   Add a new method for streaming. Instead of a blocking return, it accepts a callback or returns a reactive stream.
   ```java
   interface ModelClient {
       ChatResponse chat(List<Message> messages, List<ToolSchema> tools);

       // NEW: Streaming method
       ChatResponse streamChat(List<Message> messages, List<ToolSchema> tools, Consumer<String> onDelta);
   }
   ```
2. **`AgentEventListener` Update:**
   Add a hook for token generation.
   ```java
   default void onAssistantDelta(String delta) {}
   ```
3. **`Agent` Engine Update:**
   Update `Agent.run()` to call `streamChat` if configured, passing a lambda that triggers `listener.onAssistantDelta(delta)`. 

### Implementation Steps
1. Update `ModelClient` and `GoogleModelClient` to use Gemini's `streamGenerateContent` API endpoint.
2. Parse the Server-Sent Events (SSE) stream in the HTTP client.
3. Fire `onAssistantDelta` for every chunk received.
4. Reconstruct the full text at the end of the stream and return the final `ChatResponse` containing the aggregated text and any `ToolCalls`.

---

## 2. Context Window Management (History Truncation)
**Goal:** Prevent `400 Token Limit Exceeded` errors during long conversations by managing the session history before sending it to the LLM.

### Architecture Changes
1. **New Interface `ContextStrategy`:**
   ```java
   public interface ContextStrategy {
       List<Message> optimize(List<Message> history);
   }
   ```
2. **Implement `SlidingWindowStrategy`:**
   Keeps the `SYSTEM` prompt, but drops the oldest `USER`/`ASSISTANT`/`TOOL` messages if the list exceeds a certain number of turns.
3. **Update `AgentConfig`:**
   ```java
   ContextStrategy contextStrategy = new SlidingWindowStrategy(20); // Keep last 20 messages
   ```

### Implementation Steps
1. Create `ContextStrategy` interface and default implementations.
2. In `Agent.run()`, before calling `modelClient.chat(...)`, pass the raw `session.getMessages()` through the `ContextStrategy`.
3. Only send the optimized/truncated list to the LLM. The actual `AgentSession` on disk retains the full history.

---

## 3. Asynchronous Tool Execution (Parallel Tools)
**Goal:** Execute multiple tools requested by the LLM simultaneously (e.g., fetch weather for London and NY at the same time) to dramatically reduce latency.

### Architecture Changes
1. **Adopt Java 21 Virtual Threads:**
   Since Lambda targets Java 25, we can use `Executors.newVirtualThreadPerTaskExecutor()` for lightweight, high-performance concurrency without thread-pool management.

### Implementation Steps
1. In `Agent.run()`, locate the loop: `for (ToolCall call : toolCalls)`.
2. Replace it with asynchronous execution using `CompletableFuture`:
   ```java
   try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
       List<CompletableFuture<Message>> futures = toolCalls.stream()
           .map(call -> CompletableFuture.supplyAsync(() -> executeTool(call, session), executor))
           .toList();

       // Wait for all tools to finish
       CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

       // Append all TOOL messages in order
       for (var future : futures) {
           session.getMessages().add(future.get());
       }
   }
   ```
3. Ensure `SessionStore` metadata map is thread-safe (e.g., `ConcurrentHashMap`) so multiple tools can write state simultaneously.

---

## 4. Cancellation Tokens
**Goal:** Allow developers to gracefully interrupt long-running tool executions and agent loops.

### Architecture Changes
1. **New Class `CancellationToken`:**
   ```java
   public final class CancellationToken {
       private volatile boolean cancelled = false;
       public void cancel() { this.cancelled = true; }
       public boolean isCancelled() { return cancelled; }
       public void throwIfCancelled() { if (cancelled) throw new CancellationException(); }
   }
   ```
2. **Update `ToolInvocationContext`:**
   Add `CancellationToken token;`
3. **Update `Agent.run()` Signature:**
   Allow passing a token: `AgentResult run(String sessionId, String userInput, CancellationToken token)`

### Implementation Steps
1. Create the `CancellationToken` class.
2. In `Agent.run()`, check `token.throwIfCancelled()` at the start of every iteration step.
3. Pass the token into `ToolInvocationContext`. Long-running tools (like downloading a file) should periodically check `context.getToken().isCancelled()` and abort if true.

---

## 5. Multi-Modal Support (Images, Audio, PDF)
**Goal:** Allow the agent to process non-text inputs, aligning with modern LLM capabilities (Gemini 2.5/3.1).

### Architecture Changes
1. **New `ContentPart` Interface:**
   ```java
   public interface ContentPart { String getType(); }
   ```
2. **Implementations:**
   ```java
   public record TextPart(String text) implements ContentPart {}
   public record ImagePart(String mimeType, byte[] data) implements ContentPart {} // or Base64 String
   ```
3. **Refactor `Message`:**
   Change `String content` to `List<ContentPart> parts`.
   *(Maintain a helper `getContent()` that extracts text for backward compatibility).*

### Implementation Steps
1. Refactor `Message.java` to handle a List of `ContentPart` instead of a single string.
2. Update `Message.fromJson()` and `toJson()` to serialize the parts correctly in the JSONL files.
3. Update `GoogleModelClient` to map `ImagePart` to Gemini's `inlineData` (mimeType and base64 encoded data) when building the API request.
4. Create a test `examples/multimodal-chat` where the user passes a path to a `.jpg`, and the `Agent` adds it as an `ImagePart` to the `USER` message.

---

## Suggested Phased Roadmap

* **Phase 5 (Data & Scale):** Multi-Modal Support + Context Window Management. (Gets the agent ready for heavy, complex inputs).
* **Phase 6 (Performance):** Parallel Tool Execution + Cancellation Tokens. (Makes the agent fast and safe).
* **Phase 7 (UX/UI):** Streaming Responses. (Polishes the user experience).
