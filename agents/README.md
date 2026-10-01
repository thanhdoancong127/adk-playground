# agents

Agent factories, prompts, tools, routing and guardrails. Depends on
`domain` and ADK. **The LLM adapter is banned here** — a `BaseLlm` is
injected (ADR-0001). A new agent is a new package under `agents/`, never a module.
