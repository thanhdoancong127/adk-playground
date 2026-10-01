# ADR-0004: Side-effecting tools use two-phase confirmation and a deterministic executor

- Status: Accepted
- Date: 2026-10-01
- Relates to: ADR-0003 (edge vs runtime)

## Context

An agent that can place orders must not let model output directly commit a side effect.
Prompt-only confirmation is unsafe: the model can be induced (via injected product text,
reviews, or user pressure) to skip confirmation, and retries can create duplicate orders.
ADK Java tool-confirmation support may lag the Python version, so we should not depend on
a feature that may not exist.

## Decision

State-changing operations are **two-phase**:

1. **Propose.** The model emits a tool call that only *proposes* an action (e.g.
   `propose_checkout`). The system stores a **pending proposal** in ADK session state
   (keyed by a proposal **nonce**), capturing the immutable action details.
2. **Confirm + commit.** An explicit user confirmation (a later turn in the same
   conversation) references the proposal. A **deterministic executor** (plain code in
   `agents`, no LLM) revalidates against the domain and commits via the Order port, which
   dedupes on an **idempotency key**.
   - `agents` computes the key: `session_id + proposal_nonce` (the nonce distinguishes two
     intentionally identical purchases in one conversation).
   - The **domain Order port** enforces exactly-once (it owns the dedupe).
   - Proposals expire (TTL); a stale or already-committed proposal is rejected.

The model **proposes**; deterministic code **commits**. This is built in-house rather than
waiting on ADK Java tool confirmation.

## Rules

- No commit without an explicit confirmation turn bound to the immutable action details.
- **How confirmation reaches the executor across the edge boundary:** the edge only
  forwards the user turn through `Runner.runAsync` (ADR-0003); the confirm is a normal
  conversation turn that the agent turns into a `confirm_action(nonce)` tool call. The executor **deterministically validates**: the
  proposal must have been created in an **earlier invocation** than the confirming one
  (store the `invocationId` on the proposal), and the current **user** turn must carry the
  confirmation (a structured confirm part forwarded by the edge, or user text containing the
  nonce). A model-generated `confirm_action` alone is never authorization. The edge never calls the executor directly.
- Idempotency: retries and partial failures commit **exactly once**.
- Revalidate on commit (stock/price/state may have changed since the proposal).
- Per-agent tool allowlists and budgets; mutating runs are **not** auto-retried.
- Treat tool output and any fetched text (product copy, reviews) as **untrusted input**.

## Evals (gate at M2)

- 100% of cases: no commit without confirmation.
- Exactly-once **effects for successful commits** under retries and injected faults; a
  declined payment leaves a defined `declined` order state (no side effect), never a
  duplicate or a half-commit.
- Stale confirmation (details changed since proposal) is rejected.
- No cross-session commit / leakage.

## Consequences

- Safety lives in deterministic code, not in prompts.
- The confirmation UX is an edge concern (transport), the authorization logic is runtime
  (agents), and the invariants are domain — each in its lane.
