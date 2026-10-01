# Technical notes & gotchas

## Toolchain (WSL2, no sudo)

Compilation targets `release=17`; the toolchain installed under `~/.local/opt/` is JDK 21:

```bash
export JAVA_HOME=~/.local/opt/jdk-21.0.12.1+1
export PATH="$HOME/.local/opt/apache-maven-3.9.9/bin:$JAVA_HOME/bin:$PATH"
```

## Keeping an Adapter in Sync (ADK Java)

- ADK Java models available: `Gemini`, `Claude`, `ApigeeLlm` — **no** OpenAI/LiteLlm.
  Any OpenAI-compatible provider needs a custom `BaseLlm`.
- The `openai` API type inside `ApigeeLlm` routes through the Apigee proxy, not
  directly to the endpoint — not a general-purpose bridge.
- `LlmRequest` gives `contents()` (List<Content>), `getFirstSystemInstruction()`,
  `config()`, `tools()`. `BaseLlm` requires `generateContent(...)` and `connect(...)`.

## opencode-go endpoint

- Requires the `x-opencode-session` header (a `MissingSessionID` error otherwise).
- Bearer token + `POST {base}/chat/completions`; response is `choices[0].message.content`.
- Key lives in Infisical (`/opencode`), injected into `.env`; never committed.

## Maven / build

- Multi-module reactor: build from the **root** (`mvn -B package` / `-B verify`); the
  single executable is `playground-cli/target/playground-cli-*.jar` (shaded fat jar).
- To build/test one module plus its upstream deps, run from the root with `-am`:
  `mvn -B -pl playground-cli -am package`. Do **not** run `exec:java` at the parent
  (the parent has no main class) — run the fat jar instead:
  `java -jar playground-cli/target/playground-cli-*.jar "<prompt>"`.
- On the first build Maven downloads all deps (a few minutes); later builds are fast.
- `target/` is gitignored.
- `SLF4J: No providers were found` is a harmless warning (google-adk logs via SLF4J).

## Docker

- The image is a **CLI invocation**, not a service. Build with `docker compose build`;
  run with `docker compose run --rm app "<prompt>"` (uses `.env`).
- The Dockerfile copies **all module POMs** before the sources (reactor-aware) and then
  `adk-openai-adapter/src`, `playground-domain/src`, `playground-agents/src`,
  `playground-cli/src`. Copying only the root `pom.xml` + `src/` no longer works.
- Optional offline profile: `docker compose --profile local up -d ollama`, then
  `docker compose exec ollama ollama pull qwen3:4b` (download the model explicitly),
  and set `OPENCODE_MODEL=qwen3:4b` + `OPENCODE_BASE_URL=http://ollama:11434/v1`.
  Set `OLLAMA_CONTEXT_LENGTH` (the compose service sets `8192`) so the agent prompt +
  tools are not silently truncated.
- **Compose override caveat:** the `app` service sets
  `OPENCODE_BASE_URL: ${OPENCODE_BASE_URL:-http://ollama:11434/v1}`, which overrides
  `env_file`. If your `.env` omits `OPENCODE_BASE_URL`, `docker compose run app` targets
  Ollama instead of the adapter's own opencode default. Set it explicitly in `.env`.
- **Header caveat:** the adapter always sends `x-opencode-session` (opencode-go requires
  it). Against a non-opencode endpoint it is harmless but unnecessary; strip it if you
  point at a strict server.

## Module boundaries (ADR-0001)

- Boundaries are **enforced by the build**: `maven-enforcer-plugin` `bannedDependencies`.
  - `playground-domain` bans `com.google.adk:*` and `com.playws:*`.
  - `playground-agents` bans `com.playws:adk-openai-adapter`
    (`searchTransitive=true`) — the model is injected, never imported.
  - `adk-openai-adapter`'s "no internal deps" rule is **not yet enforced** (convention only).
- Verify it still bites after edits: temporarily add `google-adk` to
  `playground-domain` → `mvn -pl playground-domain validate` must fail.
- A package becomes a module only if it has its own dependency boundary, a separate
  consumer, or its own lifecycle. Adding an agent/tool/prompt never creates a module.

## Testing gotcha

- Tool-call compatibility is **not** the same as chat compatibility. A model that
  answers text may still fail to emit valid function calls — verify M0 explicitly.
