<div align="center">

# Lambda AI Agent Framework 🤖
**A Lightweight, Embeddable Java Framework for Building LLM-Powered Agents**

[![Java](https://img.shields.io/badge/Java-25+-blue.svg)](https://openjdk.org/)
[![Maven](https://img.shields.io/badge/Maven-Build-C71A36?logo=apache-maven)](https://maven.apache.org/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](https://opensource.org/licenses/MIT)
[![Status](https://img.shields.io/badge/Status-Beta-yellow.svg)]()

Lambda AI is a minimal, modular Java library designed to bring autonomous AI agent capabilities to your Java backend, Spring Boot applications, or CLI tools. 

[Quick Start](#-quick-start) • [Key Features](#-key-features) • [How it Works](#-what-is-lambda-ai) • [Documentation](docs/SystemInfo.md)

</div>

---

## 🚀 What is Lambda AI?
Lambda AI provides the essential building blocks to create conversational AI agents that can **think, remember, and act**. Unlike heavy, monolithic frameworks, Lambda AI acts as a clean, embeddable library. It handles the complex LLM communication loop so you can focus on building custom tools and business logic.

If you want to build an AI assistant in Java that can read local files, call your company's internal APIs, or interact with databases autonomously, Lambda AI is the framework you need.

## ✨ Key Features

- **🔌 Pluggable LLM Providers:** Abstracted `ModelClient` interface. Currently supports Google Gemini (OpenAI and Anthropic coming soon).
- **🛠️ Autonomous Tool Calling:** Define tools using standard Java interfaces. The agent automatically decides when to call them and maps JSON arguments to your Java methods.
- **🧠 Persistent Memory:** Built-in `JsonlSessionStore` ensures your AI agent never loses context, remembering conversations even after JVM restarts.
- **⚡ Event-Driven Architecture:** Use `AgentEventListener` to hook into the agent's thought process, allowing for real-time UI streaming and execution monitoring.
- **🛡️ Built for Production:** Robust error-handling strategies (`SEND_TO_MODEL` vs `THROW`) ensure your agent can self-heal when a tool fails.

## 💻 Quick Start

### 1. Requirements
* Java 25 or higher
* Maven 3.8+
* Google Gemini API Key

### 2. Set Your API Key
Export your Gemini API key to your environment variables:

```bash
export GEMINI_API_KEY="your-api-key-here"
```

### 3. Run the Example Agent
Clone this repository and run the pre-built CLI agent, which includes file-reading capabilities and persistent memory:

```bash
git clone https://github.com/Mr-XX23/LambdaAI.git
cd LambdaAI/examples/simple-chat-with-JSONL-file-based-session-storage
mvn clean compile exec:java
```

## 🧩 Building a Custom Tool is Easy

To give your agent a new superpower, just implement the `AgentTool` interface. The framework handles the rest.

```java
public class WeatherTool implements AgentTool {
    @Override
    public String getName() { return "get_weather"; }

    @Override
    public String getDescription() { return "Fetches the current weather for a specific city."; }

    @Override
    public String getJsonSchema() { 
        return "{ \"type\": \"object\", \"properties\": { \"city\": { \"type\": \"string\" } } }"; 
    }

    @Override
    public ToolResult execute(ToolInvocationContext ctx) {
        // Your Java business logic goes here
        String city = new JSONObject(ctx.getArgumentsJson()).getString("city");
        return ToolResult.of("The weather in " + city + " is sunny and 75°F.");
    }
}
```

## 🏗️ Use Cases
* **Spring Boot Chatbots:** Embed Lambda AI inside a Spring REST Controller to serve an intelligent customer support bot.
* **Coding Assistants:** Use the provided `FileReadTool` and `FileWriteTool` to build a local AI developer.
* **Workflow Automation:** Map your internal microservices to `AgentTool` implementations, allowing the LLM to orchestrate complex internal tasks autonomously.

## 📖 Documentation
For a deep dive into the architecture, the agent loop, and advanced configuration options, please read the [System Architecture Documentation](docs/SystemInfo.md).

---
<div align="center">
<i>Built by <a href="https://github.com/Mr-XX23">Mr-XX23</a></i><br>
If you find this project useful, please consider giving it a ⭐!
</div>
