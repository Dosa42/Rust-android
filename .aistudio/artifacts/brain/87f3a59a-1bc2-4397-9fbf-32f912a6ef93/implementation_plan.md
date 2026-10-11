# CCX Multi-Protocol Relay & Gateway Architecture for Android

Porting the complete backend schema and relay pipeline from [`BenedictKing/ccx`](https://github.com/BenedictKing/ccx) into our Android proxy daemon. The app exposes a complete CCX multiplexer on `127.0.0.1:<port>` with support for **Codex Responses** (`/v1/responses`), **Claude Messages** (`/v1/messages`), **OpenAI Chat** (`/v1/chat/completions`), and **Gemini** (`/v1beta/models`), with bidirectional cross-protocol payload conversion and the persistent Codex OAuth credentials.

---

### User Review & Critical Decisions

> [!IMPORTANT]
> **Confirmed Architectural Directives**:
> 1. **Complete CCX Multiplexer**: The proxy daemon will expose all primary CCX endpoints on `127.0.0.1:<port>`:
>    - **Codex Responses**: `POST /v1/responses`, `POST /v1/responses/compact`, `POST /v1/alpha/*`
>    - **Claude Messages**: `POST /v1/messages`, `POST /v1/messages/count_tokens`
>    - **OpenAI Standard**: `POST /v1/chat/completions`, `GET /v1/models`, `POST /v1/embeddings`, `POST /v1/images/*`
>    - **Gemini Native**: `POST /v1beta/models/*modelAction`
>    - **Prefix Support**: Support path-prefixed routing `/:routePrefix/v1/*` (e.g. `/myroute/v1/responses`).
> 2. **Cross-Protocol Relay & Normalization**:
>    - When a client sends a Codex `/v1/responses` payload to an upstream Claude or OpenAI endpoint, the relay automatically converts between CCX response items (`input`, `responses`, `tool_call`) and OpenAI/Claude message structures.
>    - Responses from upstream are normalized back into the client's expected response format (JSON or SSE stream).
> 3. **Persistent Active Account (`Codex Pro`)**:
>    - Hardcoded persistent OAuth account (`dosa042@gmail.com`) with Bearer token, refresh token, and `ChatGPT-Account-Id: af8d241c-d024-4c9b-bab1-e9e0cca8f9e8` automatically active for all Codex `/v1/responses` and OpenAI requests.

---

## 1. Overview & Core Concept

### What It Delivers
A unified local developer proxy on Android that mirrors the full backend capabilities of `BenedictKing/ccx`. Any developer tool (Codex CLI, Claude Code, Cursor, Continue, or standard OpenAI SDKs) can point its `baseUrl` to `http://127.0.0.1:8080/v1` and seamlessly route requests across heterogeneous model providers with automatic schema translation.

### Endpoint Routing Matrix

| Inbound Path | Protocol Format | Supported Upstream Providers | Conversion Logic |
| :--- | :--- | :--- | :--- |
| **`/v1/responses`** | Codex Responses API | Codex, OpenAI, Claude, Gemini | Passthrough for Codex; converted to Messages for Claude; converted to Chat for OpenAI |
| **`/v1/responses/compact`** | Codex Memory Compaction | Codex, OpenAI | Summarizes prompt history and compresses context window |
| **`/v1/messages`** | Anthropic Claude API | Claude, OpenAI, Codex | Passthrough for Claude; converted to Responses for Codex |
| **`/v1/chat/completions`** | OpenAI Chat Completions | OpenAI, Codex, Claude, xAI | Passthrough for OpenAI; converted to Responses for Codex |
| **`/v1/models`** | OpenAI Models Discovery | All active accounts | Returns aggregated models from upstream providers |
| **`/v1beta/models/*`** | Google Gemini API | Gemini, OpenAI, Claude | Maps Gemini `generateContent` to target upstream format |

---

## 2. User Experience & Visual Design

### Key Developer Experience Flows
1. **Endpoint Hub & Protocol Switcher**:
   - Live dashboard displaying all active protocol routes with one-tap copy buttons:
     - `http://127.0.0.1:8080/v1/responses` (for Codex CLI)
     - `http://127.0.0.1:8080/v1/messages` (for Claude Code)
     - `http://127.0.0.1:8080/v1/chat/completions` (for OpenAI SDKs)
2. **Interactive Multi-Protocol Console**:
   - Tabbed test runner allowing the user to test:
     - **Codex Responses Test**: Sends a raw `{"model": "gpt-4o", "input": [...]}` request to `/v1/responses`.
     - **Claude Messages Test**: Sends a raw Anthropic `{"model": "claude-3-5-sonnet", "messages": [...]}` to `/v1/messages`.
     - **OpenAI Chat Test**: Sends standard chat completions to `/v1/chat/completions`.
   - Real-time display of converted upstream payload, outbound headers, and response latency.
3. **Account & Channel Manager**:
   - Displays the hardcoded `Codex Pro (dosa042@gmail.com)` account with `FIXED SSoT` badge.
   - Allows adding additional channels (Claude, Gemini, xAI, Custom) with protocol tags.
4. **Traffic & Schema Inspector**:
   - Live log displaying inbound protocol (e.g. `[RESPONSES]`, `[MESSAGES]`, `[CHAT]`), target upstream route, and status code.

---

## 3. Key Product Decisions & Trade-Offs

### Decision 1: Protocol Multiplexing on a Single Port
- **Chosen Approach**: All endpoints (`/v1/responses`, `/v1/messages`, `/v1/chat/completions`) listen on the same single port (`127.0.0.1:8080`).
- **Why**: Allows different developer clients (e.g. Codex CLI pointing to `/v1/responses`, Claude Code pointing to `/v1/messages`) to use the exact same proxy instance simultaneously without managing multiple ports.

### Decision 2: Cross-Protocol Converter Architecture
- **Chosen Approach**: Port the core conversion logic from `converters/chat_to_responses.go` and `converters/responses_to_chat.go` into Kotlin + Rust JNI.
- **Why**: Enables Codex CLI (which only speaks `/v1/responses`) to work against standard OpenAI API keys or Claude channels, and vice versa.

---

## 4. Technical Architecture & Data Strategy

### Protocol Conversion & Relay Pipeline

```
┌────────────────────────────────────────────────────────────────────────┐
│                        Inbound Developer Clients                       │
│                                                                        │
│   Codex CLI           Claude Code             OpenAI SDK / Cursor      │
│   (POST /v1/responses)  (POST /v1/messages)  (POST /v1/chat/completions)│
└─────────────┬─────────────────────┬─────────────────────┬──────────────┘
              │                     │                     │
              ▼                     ▼                     ▼
┌────────────────────────────────────────────────────────────────────────┐
│              CCX Multi-Protocol Router (127.0.0.1:8080)                │
│                                                                        │
│   ┌────────────────────────────────────────────────────────────────┐   │
│   │ Route Dispatcher & Prefix Normalizer                           │   │
│   │ (Matches /v1/responses, /v1/messages, /v1/chat/completions)    │   │
│   └───────────────────────────────┬────────────────────────────────┘   │
│                                   │                                    │
│                                   ▼                                    │
│   ┌────────────────────────────────────────────────────────────────┐   │
│   │ Bidirectional Protocol Converter & Normalizer                  │   │
│   │                                                                │   │
│   │   • ChatToResponses (OpenAI messages -> Codex input items)     │   │
│   │   • ResponsesToChat (Codex input items -> OpenAI messages)     │   │
│   │   • ClaudeToResponses (Anthropic blocks -> Codex items)        │   │
│   └───────────────────────────────┬────────────────────────────────┘   │
│                                   │                                    │
│                                   ▼                                    │
│   ┌────────────────────────────────────────────────────────────────┐   │
│   │ Upstream Dispatcher & Channel Selector                         │   │
│   │                                                                │   │
│   │   Channel 1: Codex OAuth (dosa042@gmail.com)                   │   │
│   │   • Bearer Token + ChatGPT-Account-Id Header                   │   │
│   │   • Target: https://api.openai.com/v1/responses                │   │
│   │                                                                │   │
│   │   Channel 2: OpenAI Standard / Claude / Gemini Channels        │   │
│   └───────────────────────────────┬────────────────────────────────┘   │
└───────────────────────────────────┼────────────────────────────────────┘
                                    │ Real Outbound HTTPS
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                        Remote AI Provider APIs                         │
│       api.openai.com          api.anthropic.com       generativelanguage│
└────────────────────────────────────────────────────────────────────────┘
```

---

## 5. Implementation Steps (Execution Phase)

1. **Protocol Models & Schemas**:
   - Define CCX protocol data classes (`ResponsesRequest`, `ResponsesItem`, `ClaudeMessageRequest`, `ChatCompletionRequest`).
2. **Converter Engine (`CCXProtocolConverter.kt`)**:
   - Implement conversion from Codex `/v1/responses` format to OpenAI `/v1/chat/completions` format.
   - Implement conversion from OpenAI/Claude to Codex responses format.
3. **Multi-Protocol Server Expansion (`EmbeddedProxyServer.kt`)**:
   - Register `/v1/responses` and `/:routePrefix/v1/responses` handler.
   - Register `/v1/responses/compact` handler.
   - Register `/v1/messages` and `/:routePrefix/v1/messages` handler.
   - Register `/v1beta/models/*` handler.
   - Maintain existing `/v1/chat/completions` and `/v1/models` handlers.
4. **Rust JNI Engine Sync (`rust_bridge/src/lib.rs`)**:
   - Mirror the multi-protocol Axum routes (`/v1/responses`, `/v1/messages`, `/v1/chat/completions`).
5. **Dashboard UI Update (`ProxyDashboardScreen.kt`)**:
   - Add endpoint selector cards for Codex Responses, Claude Messages, and OpenAI Chat.
   - Update the test playground to support executing `/v1/responses` and `/v1/messages` requests directly.
