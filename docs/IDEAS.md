# IDEAS — parking lot

Anything we deliberately defer goes here, so it never silently creeps into the
repo. Reviewed occasionally; most items end up as "no".

## Infrastructure (probably forever "no")

- Spring Boot / microservices / HTTP between services → **no** (fakes only).
- Kafka / Elasticsearch / Keycloak / Kubernetes / Grafana → **no**.
- Docker Compose → **no**.
- Database server (Postgres/etc.) → **no**; in-memory fixtures only.
- Frontend / web UI → **no**; CLI only (maybe forever).

## Maybe later (behind an eval justification)

- Streaming responses in `OpenCodeLlm`.
- Proper tool-call serialization in the adapter (M0 makes it required).
- `num_ctx` tuning profiles per model.
- RAG over fake reviews (`nomic-embed-text`) — M5 option.
- Generator–critic enrichment loop — M5 option.
- Big-model baseline run of the same evals — M5 option.

## Open questions

- Which two small models to standardize M0 on (`qwen3:4b`? `qwen2.5:3b`?).
- Agent-transfer vs `AgentTool` for routing on small models (decide at M3).
