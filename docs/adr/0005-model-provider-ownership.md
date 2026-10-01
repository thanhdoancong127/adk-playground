# ADR-0005: Model & provider ownership

- Status: Accepted
- Date: 2026-10-01
- Relates to: ADR-0001 (modules), ADR-0002 (web module), ADR-0003 (edge vs runtime)

## Context

ADK resolves a model via a **registry string** (Gemini / Claude / Agent-Platform-hosted) or a
**connector** object. The connector list is **ADK-wide (mostly Python)**: `ApigeeLlm`,
`LiteLlm`, Ollama, vLLM, LiteRT — **only `ApigeeLlm` is present in ADK Java**. At the Java API,
`LlmAgent.builder().model(...)` accepts **either a model id string or a `BaseLlm` object**
(the router is a connector-level concept, not a third argument form). ADK Java 1.10.1 ships
`Gemini`, `Claude`, `ApigeeLlm` only — **not** a general OpenAI-compatible connector — so
playws talks to OpenAI-compatible endpoints through a custom `BaseLlm`.

Without a decision, provider code and model choice would leak into agents and multiply.
This ADR fixes who owns what.

## Decision

1. **The `adapter` owns OpenAI-compatible provider knowledge.** It contains the connector —
   today `OpenCodeLlm`, a `BaseLlm` for OpenAI-compatible `/chat/completions` endpoints with
   bearer auth (**text-only today**; tool calls land at M0).
   Only the `adapter` **and edge wiring classes** may reference provider classes; `agents`
   and `domain` never do (`agents` receives a `BaseLlm`; the adapter ban is enforced).
2. **The edge owns provider/model selection.** `cli`/`web` read configuration and inject a
   `BaseLlm`. **Target state:** the edge reads config, builds `AdapterSettings` (M0) and agent
   factories (M1); **today** `cli/DemoAgent` reads `OPENCODE_MODEL`, builds the `LlmAgent` and
   passes `new OpenCodeLlm(model)`, while `OpenCodeLlm` reads the rest of the env itself
   (`agents` has no sources yet). Within the
   **OpenAI-compatible family** (opencode-go, vLLM, LM Studio, llama.cpp, local Ollama),
   switching provider is a config change. Switching to a **built-in ADK connector**
   (Gemini/Claude/Apigee) is not config-only: it adds edge code + credentials, and no such
   kind-selector key exists yet (a `PLAYWS_LLM_PROVIDER` key is a possible later addition).
3. **A model is per agent.** `LlmAgent` takes a model per agent, so a later root/specialist
   split (M4) can mix models. The default is one provider, one model; the model id is a
   parameter, not a constant.
4. **Built-in connectors are wired only if needed.** Gemini/Claude/Apigee can be constructed
   by edge wiring and injected as an alternative `BaseLlm`; they add no module. This is
   **not wired today** and not config-only.
5. **Model routing (ADK router with failover) is optional** — adopt only if an eval shows a
   concrete need. It is not part of the default wiring.

## Consequences

- Provider change **within the OpenAI-compatible family** = configuration change
  (`OPENCODE_BASE_URL` / key / model). Built-in connectors need code + credentials.
  One seam (`adapter`) and one place to test protocol mapping.
- `agents` stays provider-agnostic and testable with `ScriptedLlm` (no network).
- The provider matrix and config keys live in `docs/TECHNICAL-NOTES.md` → "Models &
  providers"; keep them in sync. Env (`OPENCODE_*`) is the CLI surface; `web` binds
  `playws.llm.*` into `AdapterSettings` (ADR-0002).
