# ADR-0001: Modular monolith, modules split by dependency boundary (not business domain)

- Status: Accepted
- Date: 2026-10-01
- Amended by: ADR-0002 (adds `web`, `testkit`). Related: ADR-0003 (conversation edge, no
  orchestrator), ADR-0004 (two-phase side-effects). Per-module bans and the
  "split by dependency boundary" rule below stay in force.

## Context

`playws` is a CLI agent project (Java 17, `release=17`; toolchain JDK 21; Google ADK). We want "real"
engineering discipline but the system has **one process and no network boundary**.
A background idea (YAS) is a 25-microservice e-commerce sample; copying its
service-per-domain layout would add ceremony without adding isolation.

## Decision

Structure the project as **one executable built from four Maven modules** under a
parent POM:

| Module | Contents | Dependency rule |
|---|---|---|
| `adapter` | `BaseLlm` adapter to any OpenAI-compatible endpoint, request/response mapping | ADK + dotenv-java; no internal deps (convention, not yet enforced) |
| `domain` | Commerce model + **ports** + in-memory fakes. Plain Java. | **Banned: `com.google.adk:*` and all `com.playws:*`** |
| `agents` | Agent factories, prompts, tools, routing, guardrails, state keys | ADK + domain. **Banned: the adapter** — the model is injected via constructor |
| `cli` | Composition root + terminal I/O + session service + shaded jar | All of the above — the one executable |

Dependency graph: `cli → agents → domain`, `cli → adapter`, `cli → domain`; at M3
`web → agents, adapter, domain`. Nothing else (amended by ADR-0002: adds `testkit`, `web`).

Layering: the `domain` and `agents` boundaries are **enforced
by the build** (`maven-enforcer-plugin` `bannedDependencies`); the adapter's
"no internal deps" rule is convention for now (not yet enforced).

## Package vs module

A package becomes a module **only** if it has at least one of:

1. its own dependency boundary,
2. a separate consumer, or
3. its own lifecycle (build, run, release).

Adding an agent, tool, guardrail, or prompt **never** creates a module. A new agent
is a new **package** under `agents/`.

## Deferred

- No `evals` module: the eval runner lives in `testkit` (M1) and live mode is gated by
  `-Peval` (manually run, never in CI).
- `mcp-server` (optional M5) — expose commerce ports over **MCP** so agents
  consume them through `McpToolset`. This is a real process split (the honest way to
  separate a boundary), not a service-per-domain decomposition.

## Status

M0 ships the adapter and CLI modules; `domain` and `agents`
are empty skeletons populated at M1.

## Consequences

- One `mvn -B verify`, one executable, one image.
- The adapter and domain boundaries are testable in isolation.
- Revisit only when a genuine process boundary appears (i.e. MCP, not more POMs).
