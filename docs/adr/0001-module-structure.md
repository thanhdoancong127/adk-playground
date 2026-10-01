# ADR-0001: Modular monolith, modules split by dependency boundary (not business domain)

- Status: Accepted
- Date: 2026-10-01

## Context

`adk-playground` is a CLI agent project (Java 21 + Google ADK). We want "real"
engineering discipline but the system has **one process and no network boundary**.
A background idea (YAS) is a 25-microservice e-commerce sample; copying its
service-per-domain layout would add ceremony without adding isolation.

## Decision

Structure the project as **one executable built from four Maven modules** under a
parent POM:

| Module | Contents | Dependency rule |
|---|---|---|
| `adk-openai-adapter` | `BaseLlm` adapter to any OpenAI-compatible endpoint, wire mapping, tool-call IDs | ADK + HTTP/JSON only; no internal deps |
| `playground-domain` | Catalog/cart/order rules, store interfaces + in-memory fakes. Plain Java. | **Banned: `com.google.adk:*` and all `com.workshop.adkplayground:*`** |
| `playground-agents` | Agent factories, prompts, tools, routing, guardrails, state keys | ADK + domain. **Banned: the adapter** — the model is injected via constructor |
| `playground-cli` | Config, credentials, terminal I/O, session service, composition root, shaded jar | All of the above — the one executable |

Dependency graph: `cli → agents → domain` and `cli → adapter`. Nothing else.

Layering is **enforced by the build** (`maven-enforcer-plugin` `bannedDependencies`),
not by convention.

## Package vs module

A package becomes a module **only** if it has at least one of:

1. its own dependency boundary,
2. a separate consumer, or
3. its own lifecycle (build, run, release).

Adding an agent, tool, guardrail, or prompt **never** creates a module. A new agent
is a new **package** under `agents/`.

## Deferred

- `playground-evals` — added behind the `evals` Maven profile only when there is a
  dedicated eval runner with its own datasets and CI job.
- `playground-mcp-server` (optional M6) — expose catalog/cart over **MCP** so agents
  consume them through `McpToolset`. This is the real process split, and the honest
  version of "like YAS".

## Consequences

- One `mvn -B verify`, one executable, one image.
- The adapter and domain boundaries are testable in isolation.
- Revisit only when a genuine process boundary appears (i.e. MCP, not more POMs).
