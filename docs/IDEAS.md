# IDEAS — parking lot

Anything we deliberately defer goes here, so it never silently creeps into the repo.
Reviewed occasionally; most items end up as "no".

## Not layers / probably forever "no"

- **AI Orchestrator** → **no**; ADK owns intent/context/routing (ADR-0003).
- Microservices / HTTP between services → **no** (in-process ports + fakes). Allowed
  process splits: the HTTP edge (`web`, ADR-0002) and the optional MCP server (`mcp-server`).
- Kafka / Elasticsearch / Keycloak / Kubernetes / Grafana → **no**.
- Database server (Postgres/etc.) → **no**; in-memory fakes only.
- Frontend / web UI → **no**; CLI + HTTP API (no UI).
- Spring AI / LangChain4j → **no** (would duplicate ADK's LLM ownership, ADR-0002).

> Docker is **not** in this list: a thin-client Dockerfile + compose exist as a reproducible
> CLI runner (see TECHNICAL-NOTES → Docker). We just don't run the dev loop in Docker.

## Maybe later (behind an eval justification)

- (Deferred indefinitely, not eval-gated: streaming model deltas in the adapter; SSE at the edge is in scope at M3.)
- `num_ctx` tuning profiles per model.
- RAG over fake reviews (M5 candidate).
- Generator–critic enrichment loop (M5 candidate).
- Bigger-model baseline run of the same evals (M5 candidate).

## Required (not "later")

- Tool-call mapping in the adapter — prerequisite **M0** work (ROADMAP, spec).
- `testkit` (`ScriptedLlm`, record→replay) — M1.
- Two-phase confirmation + deterministic executor (ADR-0004) — M2.

## Open questions

- ~~Web edge at M1 or M3?~~ **decided: M3 (ADR-0002).**
- Whether to add `mcp-server` at M5.
- Which ports (if any) eventually get a real HTTP adapter behind WireMock contract tests.
