# Technical notes & gotchas

## Toolchain (WSL2, no sudo)

JDK 21 + Maven installed under `~/.local/opt/`:

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

- On the first build Maven downloads all deps (a few minutes); later builds are fast.
- `target/` is gitignored.
- `SLF4J: No providers were found` is a harmless warning (google-adk logs via SLF4J).

## Testing gotcha

- Tool-call compatibility is **not** the same as chat compatibility. A model that
  answers text may still fail to emit valid function calls — verify M0 explicitly.
