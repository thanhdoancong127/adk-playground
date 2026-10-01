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

## Configuration (.env)

| Key | Default | Meaning |
|---|---|---|
| `OPENCODE_BASE_URL` | `https://opencode.ai/zen/go/v1` | endpoint base |
| `OPENCODE_API_KEY` | *(none)* | bearer token |
| `OPENCODE_MODEL` | `deepseek-v4-flash` | model id |
| `OPENCODE_SESSION` | random per run | `x-opencode-session` header |

`.env` is gitignored; only `.env.example` is committed. `config/Env` resolves process env
first, then `.env`, then the default.

## LLM adapter

- ADK Java 1.10.1 ships `Gemini`, `Claude`, `ApigeeLlm` — **no** OpenAI/LiteLlm. Any
  OpenAI-compatible endpoint needs a custom `BaseLlm` (`adapter/OpenCodeLlm`).
- opencode-go requires the `x-opencode-session` header (else `MissingSessionID`).
- The adapter maps text only today; tool calls are **M0** (see the M0 spec).
- Tool-call compatibility is **not** chat compatibility: a model that answers text may
  still fail to emit valid function calls. Verify explicitly.

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
