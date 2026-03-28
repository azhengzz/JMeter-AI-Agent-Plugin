# Agent Loop Architecture

This document describes the Agent Loop architecture implemented for JMeter AI.

## Overview

The Agent Loop implements a Message -> LLM -> Tools -> Response -> Message cycle, similar to the Nanobot architecture. It provides:

- **Dynamic Tool Calling**: LLM can call tools to perform actions
- **Two-Layer Memory**: Long-term memory (MEMORY.md) and history log (HISTORY.md)
- **Session Management**: Persistent conversation state
- **Context Building**: Dynamic system prompt with memory and tools

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     AgentLoop                               │
│  (Core Processing Engine - Message Closed Loop)             │
└───────────────┬──────────────────────────────────────────────┘
                │
    ┌───────────┼───────────┬─────────────────┐
    │           │           │                 │
    ▼           ▼           ▼                 ▼
┌─────────┐ ┌─────────┐ ┌─────────┐   ┌─────────────┐
│Context  │ │Memory   │ │Tools    │   │Session      │
│Builder  │ │Store    │ │Registry │   │Manager      │
└─────────┘ └─────────┘ └─────────┘   └─────────────┘
```

## Components

### AgentLoop
- **Package**: `org.qainsights.jmeter.ai.agent`
- Core processing engine that orchestrates all components
- Implements the message loop iteration logic

### Tool System
- **Package**: `org.qainsights.jmeter.ai.agent.tools`
- `Tool`: Interface for agent tools
- `ToolRegistry`: Dynamic tool registration and execution
- `JMeterToolRegistry`: JMeter-specific tools

#### Available JMeter Tools
- `get_jmeter_element`: Get info about selected element
- `optimize_jmeter_element`: Analyze and optimize element
- `create_jmeter_element`: Create new JMeter element
- `lint_jmeter_elements`: Rename elements for better organization
- `wrap_http_samplers`: Wrap samplers under Transaction Controller

### Memory System
- **Package**: `org.qainsights.jmeter.ai.agent.memory`
- `MemoryStore`: Two-layer memory (MEMORY.md + HISTORY.md)
- `MemoryConsolidator`: Automatic memory consolidation when context window exceeds threshold

### Session Management
- **Package**: `org.qainsights.jmeter.ai.agent.session`
- `Session`: Conversation state with message history
- `SessionManager`: Session lifecycle and persistence

### Context Builder
- **Package**: `org.qainsights.jmeter.ai.agent.context`
- Builds system prompts and message lists for LLM calls
- Integrates memory and tool descriptions

## Configuration

Add to `jmeter.properties`:

```properties
# Enable Agent Loop (default: true)
agent.enabled=true

# Agent Loop Configuration
agent.max.iterations=40
agent.context.window.tokens=60000
agent.tool.result.max.chars=16000

# Memory Configuration
agent.memory.enabled=true
agent.memory.consolidation.threshold=0.5
agent.memory.workspace.path=${user.home}/.jmeter-ai/agent

# Session Configuration
agent.session.timeout=3600000
agent.session.max.sessions=100

# Tool Configuration
agent.tools.jmeter.enabled=true
agent.tools.filesystem.enabled=false
agent.tools.websearch.enabled=false
```

## Usage

### Basic Usage

```java
// Get the Agent Loop instance
AiService aiService = new ClaudeService();
AgentLoop agentLoop = AgentLoopFactory.getAgentLoop(aiService);

// Process a message
AgentResponse response = agentLoop.processMessage(
    "What element is currently selected?",
    "chat-session-1"
).get();

System.out.println(response.getContent());
```

### With Progress Callback

```java
AgentLoop agentLoop = AgentLoopFactory.getAgentLoop(aiService);

agentLoop.setProgressCallback(progress -> {
    System.out.println("Progress: " + progress);
});

AgentResponse response = agentLoop.processMessage(
    "Optimize the selected element",
    "chat-session-2"
).get();
```

### Swing Integration

```java
// In AiChatPanel or similar Swing component
AgentSwingWorker worker = new AgentSwingWorker(
    agentLoop,
    message,
    sessionKey,
    response -> {
        // Handle final response on EDT
        appendToChat(response.getContent());
    },
    progress -> {
        // Handle progress updates on EDT
        updateProgressIndicator(progress);
    }
);
worker.execute();
```

## Creating Custom Tools

```java
public class MyCustomTool extends AbstractTool {
    @Override
    public String getName() {
        return "my_custom_tool";
    }

    @Override
    public String getDescription() {
        return "Description of what this tool does";
    }

    @Override
    public String getParameterSchema() {
        return "{" +
            "\"type\": \"object\"," +
            "\"properties\": {" +
            "  \"param1\": {\"type\": \"string\"}" +
            "}" +
            "}";
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        // Tool implementation
        return ToolResult.success("Result");
    }
}

// Register the tool
AgentLoop agentLoop = AgentLoopFactory.getAgentLoop(aiService);
agentLoop.getToolRegistry().register(new MyCustomTool());
```

## File Structure

```
org.qainsights.jmeter.ai.agent/
├── AgentLoop.java              # Core processing engine
├── AgentLoopFactory.java       # Factory for creating instances
├── config/
│   └── AgentConfig.java        # Configuration management
├── context/
│   └── ContextBuilder.java     # System prompt and message building
├── memory/
│   ├── MemoryStore.java        # Two-layer memory storage
│   └── MemoryConsolidator.java # Memory consolidation logic
├── model/
│   ├── AgentResponse.java      # Final agent response
│   ├── LLMResponse.java        # LLM response
│   ├── Message.java            # Message model
│   ├── ToolCall.java           # Tool call model
│   ├── ToolDefinition.java     # Tool definition model
│   └── ToolResult.java         # Tool execution result
├── session/
│   ├── Session.java            # Session model
│   └── SessionManager.java     # Session management
├── swing/
│   ├── AgentSwingWorker.java   # Swing worker for async execution
│   └── ProgressUpdate.java     # Progress update model
└── tools/
    ├── AbstractTool.java       # Base tool implementation
    ├── JMeterToolRegistry.java # JMeter tool registry
    ├── Tool.java               # Tool interface
    ├── ToolRegistry.java       # Tool registration and execution
    ├── ValidationResult.java   # Parameter validation result
    └── junit/
        ├── CreateJMeterElementTool.java
        ├── GetJMeterElementTool.java
        ├── LintElementsTool.java
        ├── OptimizeJMeterElementTool.java
        └── WrapSamplersTool.java
```

## Memory Storage

Files are stored in `~/.jmeter-ai/agent/`:

```
~/.jmeter-ai/agent/
├── memory/
│   ├── MEMORY.md       # Long-term facts and knowledge
│   └── HISTORY.md      # Conversation log (grep-searchable)
└── sessions/
    ├── chat-session-1.session
    └── chat-session-2.session
```

## Tool Calling Flow

1. User sends message
2. AgentLoop builds context (system prompt + history + tools)
3. LLM processes and may request tool calls
4. AgentLoop executes requested tools
5. Tool results are fed back to LLM
6. LLM generates final response
7. Response is returned to user

## Error Handling

- Tool execution errors are returned to the LLM for recovery
- Max iterations limit prevents infinite loops
- Memory consolidation falls back to raw archive if AI fails
- Sessions are automatically cleaned up when expired

## Thread Safety

- `ToolRegistry` uses `ConcurrentHashMap`
- `SessionManager` is thread-safe
- Swing operations must use `AgentSwingWorker`
- AI calls are executed in background thread

## Future Enhancements

- Sub-agent system for parallel task execution
- MCP server integration for external tools
- Skills system for reusable prompts
- Web search tools
- File system tools (with security restrictions)
