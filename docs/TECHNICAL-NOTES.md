# Technical notes & gotchas

## Toolchain (WSL2, no sudo)

Compilation targets `release=17`; the installed toolchain is JDK 21:

```bash
export JAVA_HOME=~/.local/opt/jdk-21.0.12.1+1
export PATH="$HOME/.local/opt/apache-maven-3.9.9/bin:$JAVA_HOME/bin:$PATH"
```

A Maven wrapper (`./mvnw`) is the intended single entry point; until it is added, use the
`mvn` above.

## Ownership (who does what)

- **ADK** owns the conversation/tool loop: intent, context, session state, routing, tool
  execution, callbacks.
- **`adapter`** owns provider HTTP/JSON — a plug, not a layer.
- **`domain`** owns commerce truth: stock, prices, order state machine, idempotency,
  invariants — deterministic, LLM-free.
- **`web`/`cli`** (the edge) own transport, session mapping, validation, wiring — never
  intent or routing.
- Never retry a whole **mutating** agent run (tools may have side effects); use a
  deterministic executor + idempotency (ADR-0004).

## Models & providers

ADK resolves a model via a **registry string** (Gemini / Claude / Agent-Platform-hosted) or a
**connector** object. The connector list is **ADK-wide (mostly Python)**: `ApigeeLlm`,
`LiteLlm`, Ollama, vLLM, LiteRT — **only `ApigeeLlm` exists in ADK Java**. At the Java
API, `LlmAgent.builder().model(...)` accepts **either a model id string or a `BaseLlm`
object** (a router is a connector-level concept, not a third argument form). ADK Java
1.10.1 ships **`Gemini`, `Claude`, `ApigeeLlm` only** — no general OpenAI-compatible
connector, so playws supplies one: `adapter/OpenCodeLlm`. Its contract is narrow on purpose:
OpenAI-compatible `/chat/completions` with bearer auth, **text-only today** (tool calls M0).

Who owns what (ADR-0005):
- **`adapter`** owns OpenAI-compatible provider knowledge. It is the only module holding a
  provider class; edge **wiring** classes may reference it. `agents`/`domain` never do
  (`agents` bans `adapter` in the build).
- **`cli`/`web` (the edge)** owns provider/model *selection* and injects the `BaseLlm`.
  **Target state:** the edge builds `AdapterSettings` (M0) and agent factories (M1).
  **Today:** `cli/DemoAgent` reads `OPENCODE_MODEL`
  itself, builds the `LlmAgent` and passes `new OpenCodeLlm(model)`; `OpenCodeLlm` reads the
  rest of the env (base URL, key, session) internally.
- **Built-in ADK connectors (Gemini/Claude/Apigee) are not wired** and are **not
  config-only**: switching to one needs edge code plus credentials (Google API key/ADC, an
  Anthropic client). No provider-kind selector key exists yet.

Within the **OpenAI-compatible family** (opencode-go, vLLM, LM Studio, llama.cpp, local
Ollama), switching provider is a configuration change: base URL, key **and model id**
(the id is part of the endpoint contract, not just a tuning knob).

### Provider matrix (what the adapter actually supports)

Tool calls: **not supported yet** — `OpenCodeLlm` maps text only, tool calls are **M0**.

| Provider | Wiring | Tool calls | Notes |
|---|---|---|---|
| opencode-go | `OpenCodeLlm` + `OPENCODE_BASE_URL` | M0 | default; needs the opencode-specific `x-opencode-session`, else `MissingSessionID` |
| other OpenAI-compatible (vLLM, LM Studio, llama.cpp server, cloud) | same `OpenCodeLlm`, different `OPENCODE_BASE_URL` + model id | M0 | the general case, **conditional** on bearer auth + `/chat/completions` shape |
| Ollama (local) | `OpenCodeLlm` at `…:11434/v1` (compose `--profile local`) | M0 | free/offline. Context size is a **server** setting (`Modelfile` `PARAMETER num_ctx` / `OLLAMA_CONTEXT_LENGTH`), not a request field — the OpenAI-compatible `/chat/completions` path cannot set it |
| Gemini / Claude / Apigee | ADK's built-in connector, constructed by edge wiring (no `OpenCodeLlm`) | ADK | **not wired today**; needs credentials, so not config-only |

Auth: `Authorization: Bearer <OPENCODE_API_KEY>` is the **standard** OpenAI-compatible
header and is always sent — with the key unset it goes out as an empty `Bearer `, which some
strict endpoints reject (known gotcha: the header should be skipped when the key is blank).
Only `x-opencode-session` is opencode-specific; generic endpoints ignore it. Function calling
also varies by backend:
vLLM needs `--enable-auto-tool-choice --tool-call-parser <parser>`, and Ollama depends on
the model — verify per backend rather than assuming.

### Model per agent

- A model is a **parameter**, not a fixed app constant: `LlmAgent.builder().model(...)`
  takes one, so a future root/specialist split (M4) can mix models.
- Keep the **provider/model choice at the edge** (composition root) — a model is injected
  into a factory, never read inside `agents`.
- **Today there is exactly one model id**: `OPENCODE_MODEL` applies to every agent. Per-agent
  override is **undefined** (no key naming or property map yet); M4 defines it.
- **Model routing with failover** is an option, not a commitment — adopt only if an eval
  shows a concrete need. The default is one provider, one model.

## Configuration (.env)

| Key | Default | Meaning |
|---|---|---|
| `OPENCODE_BASE_URL` | `https://opencode.ai/zen/go/v1` | endpoint base — the actual provider selector within the OpenAI-compatible family |
| `OPENCODE_API_KEY` | *(none)* | bearer token |
| `OPENCODE_MODEL` | `deepseek-v4-flash` | model id sent to the endpoint; applies to every agent today (per-agent override undefined — see M4) |
| `OPENCODE_SESSION` | random UUID **per `OpenCodeLlm` instance** | `x-opencode-session` header — **opencode-go-specific**; today one static agent is built per run, so instance and run coincide |

`.env` is gitignored; only `.env.example` is committed. `config/Env` resolves process env
first, then `.env`, then the default.

## LLM adapter

- ADK Java 1.10.1 ships `Gemini`, `Claude`, `ApigeeLlm` — **no** OpenAI/LiteLlm. Any
  OpenAI-compatible `/chat/completions` endpoint needs a custom `BaseLlm`
  (`adapter/OpenCodeLlm`).
- opencode-go requires the `x-opencode-session` header (else `MissingSessionID`).
- Tool calls **are implemented (M0)**: `OpenAiWire` maps text + `tool_calls` <-> ADK
  `FunctionCall`/`FunctionResponse`, keeps provider call ids (synthesizes `call_<uuid>` when the
  provider sends none), and `OpenCodeLlm` sends `role:"tool"` results back. See
  `docs/specs/m0-tool-call-round-trip.md`.
- **A failed turn must set `errorCode`, not just `errorMessage`.** ADK's `BaseLlmFlow` drops a
  response that has no `content` and no `errorCode`, so a provider error (HTTP 500, auth, quota)
  ends the turn as a **silent empty turn** — no event, no exception. `OpenAiWire.error(...)` sets
  `errorCode=OTHER` for this reason; `ProviderFailureTest` guards it.
- Tool-call compatibility is **not** chat compatibility: a model that answers text may
  still fail to emit valid function calls. Verify explicitly.
- `stream=true` is not sent (M0 is non-streaming); `connect()` is unsupported.

### Live smoke (manual, never in CI)

```bash
mvn -pl adapter test -Dgroups=live -Dsurefire.excludedGroups=
```

- Registers one real `FunctionTool` (`getTime`), reads config via `Env` (so `.env` is honored),
  and asserts the live provider emits a tool call that is summarized back.
- Surefire's forked JVM runs with cwd = **repo root** (set in `adapter/pom.xml`); otherwise `Env`
  finds no `.env` and the provider answers `401 Missing API key`.
- Result recorded 2026-10-01: **failed — provider quota exhausted**, HTTP 429
  `GoUsageLimitError` (`limitName: monthly`) from opencode-go. The adapter surfaced it correctly
  (`HTTP 429: ...`); the test must be re-run once the monthly Go limit resets. All 44 offline
  tests pass (`mvn -B verify`).

## Record -> replay (the key dev tool)

- `RecordingLlm` (in `testkit`) wraps any `BaseLlm` and writes one JSONL cassette per
  session: each request/response, the model id, the date, tool calls and results.
- `ReplayLlm` replays a cassette and **fails with a diff** when a request does not match.
- Replay runs inside `mvn verify` (offline, deterministic). Live evals are tagged
  `@Tag("live")`, excluded by surefire by default, and run by hand
  (`-Dgroups=live -Dsurefire.excludedGroups=`); never in CI. A stale cassette must fail replay.

## Evals

- Eval cases live as data (`evals/*.jsonl`, or `testkit/src/test/resources/evals/` once `testkit` exists): input turns, expected/forbidden tool calls, expected
  domain state after the turn. The **runner** lives in `testkit`; evals run as **tests** behind the `-Peval` profile (M1).
  Live evals never run in CI.
- The runner reports pass rate, tool-argument validity, and a routing confusion matrix.

## Build / run

- Build from the root: `mvn -B package` (`-B verify` runs the tests). Evals/record live in
  `testkit` (test scope) and run as tests, e.g. `mvn -Peval -pl cli -am verify` (profile added at M1) — not from the
  CLI jar. The CLI executable
  is `cli/target/cli-*.jar` (shaded fat jar). At M3 a second executable (`web`, Spring Boot
  jar) is added per ADR-0002.
- One module + its upstream deps: `mvn -B -pl cli -am package`. Do **not** run `exec:java`
  at the parent (no main class); run the fat jar:
  `java -jar cli/target/cli-*.jar "<prompt>"`.
- `SLF4J: No providers were found` is a harmless warning from google-adk.

## Docker

- A thin-client `Dockerfile` + `docker-compose.yml` exist for a reproducible CLI run
  (not the dev loop). `docker compose run --rm app "<prompt>"` uses `.env`.
- The Dockerfile copies **all module POMs** before sources (reactor-aware), then each
  `adapter/src … cli/src`, and packages `cli/target/cli-*.jar`.
- **Compose override caveat:** the `app` service sets
  `OPENCODE_BASE_URL: ${OPENCODE_BASE_URL:-...}`. If `.env` omits it, the default wins —
  set it explicitly for the endpoint you want.

## Spring Boot (web, from M3 — ADR-0002)

- Boot BOM imported **only** in `web`; a BOM in the parent would leak Jackson/Netty/Guava
  versions into adapter/agents/cli.
- Prefer **Boot 3.x** (Jackson 2) to stay aligned with ADK 1.10.1; verify with
  `mvn dependency:tree -pl web`.
- Conversation state is not stored on controllers or arbitrary singletons; a singleton
  `InMemorySessionService`/`Runner` is fine (that is where ADK keeps sessions).
