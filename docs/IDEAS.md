# IDEAS — parking lot

Anything we deliberately defer goes here, so it never silently creeps into the
repo. Reviewed occasionally; most items end up as "no".

## Infrastructure (probably forever "no")

- Spring Boot / microservices / HTTP between services → **no** (fakes only; the
  only allowed process split is the optional MCP server in ADR-0001).
- Kafka / Elasticsearch / Keycloak / Kubernetes / Grafana → **no**.
- Database server (Postgres/etc.) → **no**; in-memory fixtures only.
- Frontend / web UI → **no**; CLI only (maybe forever).

> Docker is **not** in this list: a thin-client Dockerfile + compose exist as a
> reproducible CLI runner (see TECHNICAL-NOTES → Docker). We just don't run the
> dev loop in Docker.

## Maybe later (behind an eval justification)

- Streaming responses in `OpenCodeLlm`.
- `num_ctx` tuning profiles per model.
- RAG over fake reviews (`nomic-embed-text`) — M5 option.
- Generator–critic enrichment loop — M5 option.
- Big-model baseline run of the same evals — M5 option.

## Required (not "later")

- Tool-call serialization in the adapter — prerequisite **M0** work, tracked in ROADMAP.

## Open questions

- Which two small models to standardize M0 on (`qwen3:4b`? `qwen2.5:3b`?), or use the
  hosted opencode-go model throughout.
- Agent-transfer vs `AgentTool` for routing on small models (decide at M3).
