# Architecture

One process, one executable, four Maven modules, CLI only.

Module split is by **dependency boundary** (ADR-0001) — never service-per-domain.

| Module | Role |
|---|---|
| `adk-openai-adapter` | `OpenCodeLlm extends BaseLlm` + config `Env`. The only place that knows HTTP/JSON. Deps: ADK + dotenv-java. |
| `playground-domain` | Plain-Java catalog/cart/order rules + in-memory fakes. **No ADK, no internal deps** (enforced). |
| `playground-agents` | `LlmAgent`s and factories, prompts, tools, routing, guardrails. **Adapter banned** (enforced) — a `BaseLlm` is injected. |
| `playground-cli` | Composition root + terminal I/O + session service + shaded jar. The one executable. |

Dependency graph: `cli → agents → domain` and `cli → adapter`. Nothing else.

> **M0 reality:** `playground-domain` and `playground-agents` are empty skeletons.
> The only agent (`DemoAgent`) currently lives in `playground-cli` and constructs
> `OpenCodeLlm` directly; it moves to `playground-agents` with an injected `BaseLlm`
> at M1.

## Layers

1. **CLI / runtime** (`playground-cli`) — `DemoRunner` (one-shot) and later a REPL.
   Both drive `InMemoryRunner`, which owns the ADK session service.
2. **Agents** (`playground-agents`, planned) — currently `DemoAgent` in cli; later a
   root `Concierge` that transfers to sub-agents. A new agent is a **package**, not a module.
3. **Domain fakes** (`playground-domain`, planned) — in-memory fixtures and fake
   service code: `Product`, `Inventory`, `Cart`. Plain Java method calls exposed to
   ADK as tools.
4. **LLM boundary** (`adk-openai-adapter`) — `OpenCodeLlm` + `Env`. The only place
   that knows about HTTP, JSON, and the endpoint. The **model id** is a constructor
   argument; `OPENCODE_MODEL` is read by the caller (`DemoAgent` in cli), not the adapter.

## The LLM boundary

ADK Java ships only `Gemini`, `Claude`, and `ApigeeLlm`. `OpenCodeLlm` bridges any
**OpenAI-compatible** `/chat/completions` endpoint into ADK:

```
LlmRequest  --(contents + systemInstruction)-->  OpenAI messages  -->  POST /chat/completions
LlmResponse <--(Content via Part.fromText)-------  choices[0].message.content
```

Config resolution (`config.Env`): process env first, then `.env` (dotenv-java), then a
default. `.env` is gitignored; only `.env.example` is committed.

| Key | Default | Meaning |
|---|---|---|
| `OPENCODE_BASE_URL` | `https://opencode.ai/zen/go/v1` | endpoint base |
| `OPENCODE_API_KEY` | *(none)* | bearer token |
| `OPENCODE_MODEL` | `deepseek-v4-flash` | model id |
| `OPENCODE_SESSION` | random UUID | `x-opencode-session` header |

## Known limitations of the current adapter

- **Text only.** Tool calls are not yet serialized/parsed. Non-text parts
  (`functionCall`/`functionResponse`) are **silently dropped**, and a `Content` with
  no text parts is skipped entirely. This is exactly what **M0** fixes.
- **Non-streaming.** `generateContent(stream=true)` is ignored; one response.
- **No live connection.** `connect()` throws `UnsupportedOperationException`.
- **No thinking/tool-call fields.** Only `choices[0].message.content` is read.

## Technology choices (and why)

| Choice | Why |
|---|---|
| Google ADK (Java) | Target framework to practice; tool calling + sessions + callbacks |
| Java 17 (`release=17`), Maven, 4 modules | Enforced boundaries; toolchain JDK 21; no Spring, no service-per-domain |
| `BaseLlm` adapter | ADK Java has no OpenAI/LiteLlm class |
| dotenv-java | Keep keys out of code and out of the repo |
| In-memory fakes | Behaviour test: the domain must not grow into e-commerce |

## Full diagram

See the Mermaid diagrams in the top-level [`README.md`](../README.md).
