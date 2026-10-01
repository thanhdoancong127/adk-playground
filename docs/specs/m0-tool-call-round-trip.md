# Spec: M0 — Tool-call round trip in the LLM adapter

- Status: Implemented — offline suite green (44 tests, `mvn -B verify`); **live smoke blocked on
  provider quota** (HTTP 429 `GoUsageLimitError`, monthly opencode-go limit, 2026-10-01).
- Reviewed by Codex and Claude; all findings fixed except deferred items listed under
  "Known follow-ups".
- Date: 2026-10-01
- Milestone: M0 (ROADMAP)
- Scope: **`adapter` only** (+ its tests). No other module changes in M0.

## Goal

Make `OpenCodeLlm` a **correct** bridge for tool calling between ADK and an
OpenAI-compatible `/chat/completions` endpoint: the agent receives tool declarations,
emits a tool call, ADK executes it, and the model returns a final answer that accounts
for the tool result.

Today the adapter is text-only: it sends text `messages` and reads
`choices[0].message.content`; ADK tool parts (`functionCall`/`functionResponse`) are
silently dropped.

## Non-goals (M0)

- Streaming (`stream=true` stays ignored).
- Live/duplex connection (`connect()` keeps throwing).
- Multi-agent routing, cart/session, guardrails, HTTP — later milestones.
- No new module.

## API facts (ADK 1.10.1 / google-genai 1.58.0)

Verified against the jars:

- `com.google.genai.types.Part`: `Optional<FunctionCall> functionCall()`,
  `Optional<FunctionResponse> functionResponse()`, `Optional<String> text()`;
  factory `Part.fromText(String)`.
- `FunctionCall`: `Optional<String> id()`, `Optional<String> name()`,
  `Optional<Map<String,Object>> args()`. **There is no `Part.fromFunctionCall(id,name,args)`** —
  build it: `Part.builder().functionCall(FunctionCall.builder().id(id).name(name).args(args).build()).build()`.
- `FunctionResponse`: `Optional<String> id()`, `Optional<String> name()`,
  `Optional<Map<String,Object>> response()`.
- `LlmRequest.tools()` returns `Map<String, BaseTool>`;
  `BaseTool.declaration()` returns `Optional<FunctionDeclaration>`;
  `FunctionDeclaration`: `Optional<String> name()`, `Optional<String> description()`,
  `Optional<Schema> parameters()`, `Optional<Object> parametersJsonSchema()`.

## Contract: ADK <-> OpenAI mapping

Extract a pure, testable `OpenAiWire` (no HTTP; Jackson only).

### Request (ADK `LlmRequest` -> JSON body)

| ADK | OpenAI wire |
|---|---|
| `getFirstSystemInstruction()` | `messages[]` `{role:"system", content:<text>}` (only if present) |
| `Content` role `user`, text parts | `{role:"user", content:<concatenated text>}` |
| `Content` role `model`, text parts | `{role:"assistant", content:<text>}` |
| `Content` role `model`, `Part.functionCall`(s) | **one** `{role:"assistant", content:<text or omitted>, tool_calls:[{id, type:"function", function:{name, arguments:<JSON string>}}]}` |
| `Content` role `user`, `Part.functionResponse`(s) | **one message per response**: `{role:"tool", tool_call_id:<id>, content:<JSON string of response()>}` |
| `tools()` (`Map<String,BaseTool>`) | `tools:[{type:"function", function:{name, description, parameters:<JSON schema>}}]` |

Grouping rules (one `Content` may hold several parts):
- Model content: fold all text parts into one assistant `content`; fold all
  `functionCall` parts into **one** assistant message's `tool_calls` array (in order).
- If a model content has both text and calls -> a single assistant message with both.
- Each `functionResponse` part becomes its **own** `role:"tool"` message.
- A user content that mixes text and functionResponses: emit the `tool` message(s) first,
  then a `user` text message.

Value rules:
- `arguments` and tool `content` are JSON **strings**. Serialize with
  `ObjectMapper.writeValueAsString(map)`; never serialize an `Optional`.
- Mapping `FunctionDeclaration.parameters()` (`Optional<Schema>`) to OpenAI JSON Schema is
  required; use `parametersJsonSchema()` directly if present, else convert `Schema`
  recursively (`type`, `properties`, `required`, `items`, `enum`, `description`),
  **lower-casing `type`** — `Schema.type()` returns `Optional<Type>`; unwrap to `Type.Known`
  and lower-case its name (`OBJECT`->`object`) for OpenAI. Unknown
  schema fields are passed through best-effort. A tool with **no** declaration is skipped
  with a debug log (never crashes).
- **IDs:** if `functionCall.id()` is absent, synthesize `call_<uuid>` (unique across the
  whole request history — check against every id already in `LlmRequest.contents()`), not a
  per-request counter. When present, propagate it. Note: ADK may already assign an
  `adk-<uuid>` id to id-less calls; the adapter's synthesis is a **fallback**. A
  `functionResponse` maps its `id()` to `tool_call_id`; if it has no id, match by name to
  the most recent **unmatched** call of that name; if ambiguous (parallel same-name calls,
  or none unmatched), emit `errorMessage("unmatched tool response")`.

### Response (JSON -> ADK `LlmResponse`)

| OpenAI wire | ADK |
|---|---|
| `choices[0].message.content` (non-empty) | `Part.fromText(...)` |
| `choices[0].message.tool_calls[]` | one `Part` per call: `functionCall(FunctionCall.builder().id(id).name(name).args(parsedArgs).build())` |
| both present | both parts, `content` part first, then function parts |

Response rules:
- Parse `function.arguments` (a JSON string) into `Map<String,Object>`. Non-object or
  **malformed** JSON for a call: that call contributes **no Part**. When parallel calls mix
  good and bad JSON (or text + a malformed call), emit Parts for the good/text parts **and**
  add the `errorMessage`; a turn whose only content is a malformed call returns the
  `errorMessage` alone with `turnComplete(false)`.
- A response `tool_call` with **no `id`**: synthesize a response-side `call_<uuid>` (same scheme as the
  request side) so ADK can match the later `functionResponse`. A **response** id is a *new*
  id: it must be unique within the response and must not reuse an id already present in
  `LlmRequest.contents()` (else `errorMessage("duplicate tool call id: <id>")`). The parser
  therefore needs the request history — pass the `LlmRequest` to `OpenAiWire.parse`.
- `content:null`/empty with only tool_calls -> emit **no** `Part.fromText("")`.
- **`turnComplete`:** set `true` on a normal final text response (no tool calls) and
  **`false`** on any response that carries tool calls or an `errorMessage`. (In ADK's
  non-streaming flow the loop actually continues based on `Event.finalResponse()`, which
  looks at function calls/responses — not `turnComplete` — so this is consistency, not the
  mechanism.)
- `finish_reason` / `usage` are not required in M0.

### Errors: precedence

`errorMessage` reflects the **first** failure in this order: (1) invalid/empty body,
(2) `{"error":...}`, (3) empty `choices`, (4) per-call errors (no name, malformed
arguments, duplicate id). Fatal envelope errors (1-3) yield the error alone; **per-call**
errors (4) still emit the valid Parts and add the error (naming the first bad call).
Transport/IO failure is separate (`Flowable.error`).

### Errors (exact contract)

`generateContent` never throws for provider-side problems; it returns
`LlmResponse.builder().errorMessage(<msg>).turnComplete(false).build()` and the Flowable
completes. (Network/IO is different — see the last row.)

| Case | Result |
|---|---|
| non-2xx HTTP | `errorMessage("HTTP <code>: <body>")` |
| 2xx with `{"error":{...}}` | `errorMessage("provider error: <json>")` |
| 2xx with invalid/missing JSON body | `errorMessage("invalid response body: <raw>")` |
| empty/missing `choices` | `errorMessage("empty choices")` |
| duplicate response `tool_calls[].id` (repeated within the response, or reusing a request-history id) | `errorMessage("duplicate tool call id: <id>")` |
| a `tool_call` with no name | that call contributes no Part; if it is the only thing in the turn, `errorMessage("tool call without name")`; if mixed with good calls/text, keep the good Parts **and** add the `errorMessage` |
| unparseable/non-object tool `arguments` | that call contributes no Part; good Parts are kept and `errorMessage("invalid tool arguments: <raw>")` is added |
| unmatched `functionResponse` (request side) | `errorMessage("unmatched tool response")` |
| network/IO exception | `Flowable.error(io)` (transport failure is exceptional, not a response) |

## Config seam (testability)

`OpenCodeLlm` currently reads `Env` in its constructor, which blocks an in-process HTTP
test. Add a **second constructor / factory** taking explicit settings:

```java
public record AdapterSettings(String baseUrl, String apiKey, String session, String model,
                              Duration timeout, HttpClient http) {}
public OpenCodeLlm(AdapterSettings settings)   // used by tests/web and by the Env-based constructor
```

The env-based constructor becomes a thin wrapper over `AdapterSettings` (no behavior
change for CLI).

## Test strategy

The parent manages `junit-jupiter`, `assertj-core` and `maven-surefire-plugin` (done).
Declare the two test deps in **`adapter`** only — the whole M0 suite (including the
integrated round trip) lives there, so `cli` needs no test deps at M0.
Add to the parent pom: `<surefire.excludedGroups>live</surefire.excludedGroups>` and set
surefire's `<excludedGroups>${surefire.excludedGroups}</excludedGroups>`, so `@Tag("live")`
is skipped by default. Run it with
`mvn -pl adapter test -Dgroups=live -Dsurefire.excludedGroups=`.

1. **`OpenAiWireTest`** (adapter, offline): a case per row above + fixtures
   `text-only.json`, `single-tool-call.json`, `parallel-tool-calls.json`,
   `mixed-text-and-call.json`, `missing-id.json`, `provider-error.json`,
   `empty-choices.json`, `bad-arguments.json`; the invalid-body / no-name / unmatched-response
rows are tested with **inline JSON** (no fixture file). Assert exact outbound JSON and parsed parts. Id synthesis is injected (`Supplier<String>`
  on `OpenAiWire`) so tests stay deterministic: id-less calls are asserted with a fixed
  injected id; preserved ids are asserted exactly.
2. **`OpenCodeLlmTest`** (adapter, offline): point `AdapterSettings` at a
   `com.sun.net.httpserver.HttpServer` returning the fixtures; assert request body
   (tools present; tool result as `role:"tool"`) and parsed `LlmResponse`, plus each error row.
3. **Integrated round-trip** (`adapter` test): `InMemoryRunner -> OpenCodeLlm ->
   local HttpServer -> real FunctionTool -> second HTTP request -> final answer`, where the
   server scripts call-then-answer. It lives in `adapter` because `InMemoryRunner` and
   `FunctionTool` both come from `google-adk` (already a dependency), keeping M0 to one
   module. It does **not** use `ScriptedLlm`.

### Where `ScriptedLlm` lives

`ScriptedLlm` is a plain `BaseLlm` double with **no adapter dependency**. It is **M1
work**, not M0: it lands with the shopper agent it drives. Its home is a
`testkit` module from the start (ADR-0002 records the edges): by M1 there are two
consumers (`agents` tests, `cli` tests); `web` tests join at M3. A `testkit` module
satisfies ADR-0001's "separate consumer" rule. Consumers depend on it at **test scope** (a normal
jar used only in tests; not a `test-jar` classifier). M0 does not add `ScriptedLlm`; M0's
agent-level proof is the integrated test in item 3.

## Definition of done

- `mvn -B verify` green; unit tests cover the mapping + error tables.
- The integrated `Runner -> adapter -> HttpServer -> FunctionTool` round trip passes
  deterministically (no network).
- A **manual live-model smoke** (not CI): a JUnit test in `adapter` tagged `@Tag("live")`
  (excluded by default; run with `mvn -pl adapter -am test -Dgroups=live -Dsurefire.excludedGroups=`). It registers one
  demo `FunctionTool` (e.g. `get_time`), reads config via `Env` (so `.env` is honored), and
  asserts a real tool call executes and is summarized back. Command + result recorded in
  `docs/TECHNICAL-NOTES.md`.

## Risks

- Provider variance: parse `tool_calls` defensively; never crash on unknown shapes.
- Jackson mediation once Spring Boot arrives (ADR-0002) — inspect the **web** dependency
  tree, not the adapter's.
- ID matching when a provider omits `tool_call.id`.

## Known follow-ups (found during implementation / review)

- The CLI prints only `event.finalResponse()`, and an error event has no content, so a provider
  failure still reaches the terminal as an empty `Agent > ` line. Out of M0 scope (`adapter`
  only); `DemoRunner` should print `errorMessage` when `content` is empty. M1 at the latest.
- opencode-go's monthly Go quota (`GoUsageLimitError`) blocks the live smoke; re-run it after the
  limit resets, and record the result in `docs/TECHNICAL-NOTES.md`.
- Parts that map to nothing (e.g. an inline-data part) are logged and dropped rather than
  rendered; images/attachments are not an OpenAI chat-completions `content` string yet.
