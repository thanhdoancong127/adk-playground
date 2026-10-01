# Roadmap

Milestones exit on **demonstrated behaviour, not features**. Every milestone ends with a
runnable scenario and a measured result. Deterministic contract/safety cases must **all**
pass; live-model behaviour suites must reach **≥90%** with **zero critical safety
failures**. Model id + configuration are recorded with each result.

## M0 — Correct tool-call round trip

Spec: [`docs/specs/m0-tool-call-round-trip.md`](specs/m0-tool-call-round-trip.md).

- Map ADK `functionCall`/`functionResponse` <-> OpenAI `tool_calls`/`tool` messages; map
  `LlmRequest.tools()` -> request `tools`; ADK emits a call, runs it, returns the result,
  model answers.
- **Exit:** adapter contract tests (in-process `HttpServer` stub) pass; one integrated
  `Runner -> adapter -> tool -> answer` test passes. (The first JSONL cassette is recorded
  at M1, when `testkit`/`RecordingLlm` exists.)
- **Fake:** the HTTP model stub and all commerce (none exists yet).

## M1 — Read-only shopper (agent core)

- Ports + in-memory fakes + `testkit`; a ShoppingAgent with read-only tools
  (search / product detail / stock / price).
- **Exit:** 20-case replay eval (≥90%), 100% schema-valid tool arguments, zero
  hallucinated SKUs; CLI runs the scenario.
- **Fake:** everything.

## M2 — Safe commerce actions

- Cart and Order ports; **two-phase confirmation** (ADR-0004); idempotency keys; a Payment
  fake with decline/timeout injection. (Customer/Shipping/Promotion fakes are added only
  when an M2+ scenario needs them.)
- **Exit:** 100% "no commit without confirmation"; exactly one order under retries and
  fault injection; M1 suite does not regress.
- **Fake:** everything; Payment, Shipping, Promotion stay fake permanently.

## M3 — Conversation edge (HTTP)

- `web` (Spring Boot): HTTP + SSE, one session per conversation
  (`InMemorySessionService`), request validation, an edge filter. Ephemeral sessions.
- **Exit:** the same eval suite over HTTP matches CLI pass rates; **N = 8** concurrent
  conversations show no cross-talk; confirmation works across requests in one conversation.
- **Fake:** everything, plus auth (a static key).

## M4 — Evaluated specialization

- A root agent with Support / Sales / Recommendation sub-agents, **added only where the
  M1–M3 evals show the single agent failing**.
- **Exit:** routing confusion matrix — **each class recall ≥90%**, not just overall accuracy;
  no regression in earlier suites.
- **Fake:** everything.

## M5 — Reproducible reference

- A port conformance suite per port (run against the fakes); optional `mcp-server`
  exposing ports as MCP tools.
- **Exit:** every fake passes its conformance suite; MCP tools pass the same suite (if
  built); a fresh checkout runs the documented journey; recorded regressions replay
  deterministically; the pinned live suite meets its gate.
- **Fake:** everything, permanently.

## What fades

| Layer | Now | Later (optional, not required for "done") |
|---|---|---|
| LLM | opencode-go (OpenAI-compatible) | local model (Ollama) or another provider |
| Commerce ports | in-memory fakes | a real API behind a port (WireMock contract first) |
| Channels | CLI, HTTP | Chat / voice adapters on the same contract |
| Auth / gateway | static key / none | real auth + gateway **when deployment requires it** |

## Deferred indefinitely

Web UI, persistence beyond in-memory, streaming the model response (SSE is the edge, not
the adapter), Kubernetes, Kafka, Elasticsearch, Keycloak, Spring AI, a vector DB, an eval
SaaS platform. Process splits stay limited to the two front doors (HTTP API, optional MCP
server) — no microservices.
