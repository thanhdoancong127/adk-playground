# ADR-0002: Spring Boot as an HTTP composition root in `web`

- Status: Accepted (decision); implementation deferred to M3
- Date: 2026-10-01
- Amends: ADR-0001 — adds `web` (a second executable) and `testkit` (test scope), and
  extends its dependency graph and "one executable" consequence. Per-module bans stay.

## Context

The project is CLI-first. Provider/model ownership is fixed separately in ADR-0005
(the adapter owns providers; the edge injects a `BaseLlm`). The owner wants Spring Boot to serve agents over HTTP with real
DI/config/lifecycle wiring. Earlier the README non-goal said "no Spring Boot"; the owner
overrides that. This ADR records the override and constrains it. (ADR-0001 itself never
mentioned Spring, and there is no enforcer rule that bans it.)

## Decision

Add a **new module `web`** — a peer of `cli`, an HTTP composition
root. ADR-0001's dependency graph is amended to:

```
cli → agents → domain
cli → adapter, domain
web → agents, adapter, domain  (new)
```

- No other module may depend on `web`. This is **convention for now** (like the adapter
  rule); add an enforcer rule if a second consumer appears.
- `domain` still bans ADK; `agents` still ban the adapter (model injected).

Packaging:

- `cli` keeps the **shade** fat jar (CLI entry point).
- `web` uses **`spring-boot-maven-plugin` repackage** with an explicit,
  web-local `<version>` + execution. Do **not** shade both.

Spring pieces to use: `spring-boot-starter-web`, `-validation`, `-actuator`;
`@ConfigurationProperties` for `playws.llm.*`; `@Bean` wiring for the adapter
(plain-Java config injection), domain ports, root agent, and ADK `Runner`.
Avoid: Spring AI, Spring Data/DB, Spring Security, cloud, gateway.

## Boot BOM placement and Jackson

- Import the Boot BOM in **`web`'s own `<dependencyManagement>` only** (or use
  `spring-boot-starter-parent` for that module deliberately). Importing it in the parent
  would leak Jackson/Netty/Guava versions into adapter/agents/cli. This is the main risk.
- **Boot line:** prefer **Boot 3.x** (Jackson 2) so the adapter's Jackson 2 stays aligned
  with ADK 1.10.1. If Boot 4 (Jackson 3, `tools.jackson.*`) is chosen, it coexists with
  Jackson 2 only because packages differ — verify explicitly and pin. Verify with
  `mvn dependency:tree -pl web` (the adapter tree cannot reveal web mediation).

## Ownership: who calls the LLM

```
HTTP controller → ADK Runner → agent → BaseLlm adapter → provider
                              → ADK tools → plain domain
```

ADK owns the conversation/tool loop; the adapter owns provider calls; Spring owns
construction + lifecycle. **Spring AI is out of scope** — it adds a second model/tool
abstraction and a competing execution path. (Technically Spring AI could sit behind a
`BaseLlm` bridge, but that is redundant here; excluding it is an ownership decision, not
an impossibility.)

## testkit

Shared test doubles (`ScriptedLlm`, `RecordingLlm`/`ReplayLlm`, fixtures, conformance
suites) live in a `testkit` module consumed at **test scope** by `agents`, `cli` and `web`.
`testkit` must not depend on `agents` (avoids a reactor cycle).

## State and concurrency

- A singleton `InMemorySessionService`/`Runner` bean is fine (that is where ADK keeps
  session state). What is forbidden is **request/mutable conversation state on the
  controller or other arbitrary singletons**.
- One **session per conversation** (not per request); define a TTL / explicit-delete rule so
  in-memory sessions do not grow unbounded. Confirmation (ADR-0004) must work across
  requests within a conversation.
- Verify runner concurrency; propagate cancellation/timeouts. **No blanket retries around
  a whole agent run** (tools may have side effects).

## Config injection

`playws.llm.*` (`baseUrl`, `model`, `apiKey`, `timeout`; the **session** is per conversation)
binds to an
`@ConfigurationProperties` record and is passed to the adapter through its plain-Java
`AdapterSettings` constructor (see the M0 spec) — not read from the environment.

## First slice (M3 — the conversation edge)

The agent core must be good first, so the HTTP slice lands at **M3** (ROADMAP): a thin
edge over the M1 shopper — a conversational endpoint (`POST /api/conversations/{id}/messages` + an SSE stream — not a
`/catalog/query` domain endpoint), one session per conversation, plus a health endpoint and a dedicated response DTO. No commerce or routing
logic in the edge.

Acceptance: the **same M1 shopper scenario passes through both CLI and HTTP** using a
deterministic fake model (`testkit`), N concurrent conversations show no cross-talk, and
confirmation works across requests. The shared scenario lives **outside `web`** so agent
tests still run without a Spring context.

## Consequences

- Two composition roots sharing one domain/agents/adapter — no duplicated logic.
- ADR-0001 is amended (extra module + second executable); its module bans are unchanged.
- ROADMAP's "deferred indefinitely" list must note the HTTP front door as an allowed
  process split (alongside the optional M5 `mcp-server`).
