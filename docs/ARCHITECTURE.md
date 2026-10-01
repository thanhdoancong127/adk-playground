# Architecture

One process per executable. **Four Maven modules today** (`adapter`, `domain`, `agents`,
`cli`); `testkit` joins at M1 and `web` at M3, `mcp-server` is optional at M5. Split by
**dependency boundary** (ADR-0001), never service-per-domain. CLI now; HTTP edge from M3.

## Layers and ownership

```
Channels        CLI (now) · HTTP/SSE clients (M3). Web, mobile, chat, voice are clients.
   │
Conversation    cli / web  (the two composition roots = the edge)
Edge            Owns: transport, streaming, userId/session mapping, validation, API key,
                request id, passing confirmations back and forth.
                Never: intent, prompts, routing, calling commerce ports.
   │  Runner.runAsync only
Agent Runtime   ADK + agents
(ADK)           Owns: intent, context, session state, routing (sub-agent transfer),
                the tool loop, callbacks, proposals. Model = an injected BaseLlm
                (the adapter is a plug, not a layer).
   │  FunctionTools -> ports
Commerce Ports  domain
+ Fakes         Owns: truth. Stock, prices, order state machine, idempotency, invariants.
                Deterministic, no LLM involved.
```

### These are NOT layers

- **AI Orchestrator.** Intent, context, model choice and routing are already handled by the
  ADK Runner, the root agent and the injected `BaseLlm`. A second orchestrator is a second
  engine doing the same job (see ADR-0003).
- **API Gateway.** It is a filter in the edge or someone else's infrastructure — not a
  layer we build.
- **E-Commerce APIs as services.** They are in-process **ports**, not network services;
  nothing calls them over HTTP.

## Modules

| Module | Kind | Contents | Depends on |
|---|---|---|---|
| `adapter` | plain | `OpenCodeLlm`, `config/Env`; `AdapterSettings` (planned, M0) + request/response mapping | ADK, dotenv |
| `domain` | plain (JDK only) | commerce model + **ports** (introduced per milestone as scenarios require: catalog/stock/price at M1; Order/Cart/Payment at M2; Shipping/Promotion/Customer when an M2+ scenario needs them). `domain.fake` = in-memory impls + fault injection | nothing |
| `agents` | plain | agent factories, `FunctionTool`s over ports, prompts, routing, guardrails, proposal store | ADK, domain (**not** adapter) |
| `cli` | plain | CLI composition root; `chat` subcommand (planned, M1). Evals/record run as **tests** (`mvn -Peval`), not from the CLI jar. | adapter, agents, domain |
| `web` (M3) | **Spring Boot 3.x** — Boot BOM imported **only here** | HTTP/SSE composition root, session lifecycle, edge filter | adapter, agents, domain |
| `testkit` | plain, **test scope** | `ScriptedLlm`, `RecordingLlm`/`ReplayLlm`, fixtures, conformance + eval runner | ADK, domain (**not** agents — avoids a reactor cycle) |
| `mcp-server` (M5, optional) | plain + MCP SDK | exposes ports as MCP tools (a real process boundary) | domain |

Rules:
- **Ports are defined by what the agent needs, not by YAS's service list.** M1 needs only
  catalog/stock/price; Order/Cart arrive at M2; Payment is an M2 fake; Shipping/Promotion/Customer are added when a scenario needs them.
  They are **ports**, not modules or services.
- `Shopping` is the **base agent** (M1). `Support`, `Sales`, `Recommendation` are
  **packages inside `agents`**, added at M4 only when an eval justifies them.
- Fakes live in `domain` so both executables share them; `testkit` is consumed at
  **test scope** only.
- Both composition roots inject the model into agents; neither depends on the other.

## Boundaries

**Enforced now** (`maven-enforcer-plugin` `bannedDependencies`):
- `domain` bans `com.google.adk:*` and all `com.playws:*` — this blocks depending on `web`,
  not a direct Spring dependency (excluding Spring itself is planned enforcement).
- `agents` bans `com.playws:adapter` (`searchTransitive=true`) — the model is injected.
- `adapter`'s "no internal deps" is convention for now.

**Planned** (M1/M3): ArchUnit rules — `domain` imports no ADK, `agents` imports no adapter,
and **request-handling** edge classes call only `Runner` and `agents` public factories
(never `agents` internals). Wiring/config classes may construct `adapter` and `domain`
types. A `web`-only Spring rule (ban Spring AI / LangChain4j elsewhere) is added with `web`.

## Full diagram

See the Mermaid diagrams in the top-level [`README.md`](../README.md).
