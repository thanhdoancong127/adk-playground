# ADR-0003: The conversation edge, not an "AI Orchestrator"

- Status: Accepted
- Date: 2026-10-01
- Relates to: ADR-0001 (modules), ADR-0002 (web module)

## Context

A target picture proposed an **AI Orchestrator** layer (intent / context / model /
routing) sitting between the channels and ADK. That is exactly what ADK already provides:
the Runner, the root agent, sub-agent transfer, session state and the model/tool loop.
Adding a separate orchestrator would create a **second engine doing the same job** — two
sources of truth for routing and context.

## Decision

There is **no orchestrator layer**. The name for the layer above ADK is the
**Conversation Edge** (the `cli` and `web` composition roots).

- The edge owns: transport, streaming, userId/session mapping, request validation, the API
  key, the request id, and passing confirmations back and forth. **Request-handling** edge
  code calls only `Runner.runAsync` and `agents` public factories; wiring/config classes may
  construct `adapter` and `domain` types.
- ADK owns: intent, context, session state, routing, the tool loop, callbacks.
- The `adapter` is a **plug** (injected `BaseLlm`), not a layer.

A layer that does not own a distinct responsibility is deleted, not drawn. If the diagram
needs a box between channels and ADK, it is **"Conversation Edge"**, and a future API
gateway is a filter/infrastructure, not an architectural layer.

## Enforcement

- ArchUnit: **request-handling** edge classes may call only `Runner` and `agents` public
  factories (wiring/config classes may construct `adapter` + `domain` types).
- Any routing/context change must show up in the routing eval (confusion matrix), which
  can only live in `agents`.

## Consequences

- One orchestration engine (ADK); no duplicated routing logic.
- Channels (web/mobile/chat/voice) become adapters onto the same conversation contract,
  not new engines.
