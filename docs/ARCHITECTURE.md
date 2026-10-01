# Architecture

One process. One Maven module. CLI only.

## Layers

1. **CLI / runtime** — `DemoRunner` (one-shot) and later a REPL. Both drive
   `InMemoryRunner`, which owns the ADK session service.
2. **Agents** — `LlmAgent` (currently one; later a root `Concierge` that
   transfers to sub-agents).
3. **Domain fakes** — in-memory fixtures and ~200 lines of fake service code:
   `Product`, `Inventory`, `Cart`. Plain Java method calls exposed to ADK as tools.
4. **LLM boundary** — `OpenCodeLlm extends BaseLlm`. The only place that knows
   about HTTP, JSON, and the endpoint.

## The LLM boundary

ADK Java ships only `Gemini`, `Claude`, and `ApigeeLlm`. `OpenCodeLlm` bridges any
**OpenAI-compatible** `/chat/completions` endpoint into ADK:

```
LlmRequest  --(contents + systemInstruction)-->  OpenAI messages  -->  POST /chat/completions
LlmResponse <--(Content via Part.fromText)-------  choices[0].message.content
```

Config resolution (`Env`): process env first, then `.env` (dotenv-java), then a
default. `.env` is gitignored; only `.env.example` is committed.

| Key | Default | Meaning |
|---|---|---|
| `OPENCODE_BASE_URL` | `https://opencode.ai/zen/go/v1` | endpoint base |
| `OPENCODE_API_KEY` | *(none)* | bearer token |
| `OPENCODE_MODEL` | `deepseek-v4-flash` | model id |
| `OPENCODE_SESSION` | random UUID | `x-opencode-session` header |

## Known limitations of the current adapter

- **Text only.** Tool calls are not yet serialized/parsed; the adapter blankets
  `contents` into plain text. This is exactly what **M0** fixes.
- **Non-streaming.** `generateContent(stream=true)` is ignored; one response.
- **No live connection.** `connect()` throws `UnsupportedOperationException`.
- **No thinking/tool-call fields.** Only `choices[0].message.content` is read.

## Technology choices (and why)

| Choice | Why |
|---|---|
| Google ADK (Java) | Target framework to practice; tool calling + sessions + callbacks |
| Java 21 + Maven, 1 module | Matches ADK's example; no Spring, no multi-module overhead |
| `BaseLlm` adapter | ADK Java has no OpenAI/LiteLlm class |
| dotenv-java | Keep keys out of code and out of the repo |
| In-memory fakes | Behaviour test: the domain must not grow into e-commerce |

## Full diagram

See the Mermaid diagrams in the top-level [`README.md`](../README.md).
