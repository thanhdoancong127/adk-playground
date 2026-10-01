# Vision

## What this is

`playws` is a **working reference and lab for conversational commerce built on Google
ADK (Java)**. It grows **outward from the agent core**:

1. a correct LLM adapter,
2. a tested agent core that uses tools reliably,
3. a thin conversation edge (CLI, then HTTP),
4. grounded commerce tools (ports + deterministic fakes),
5. safe, state-changing workflows, and
6. justified agent specialization.

It is **not a store** and never becomes one: the in-memory fakes are **first-class
fixtures**, not placeholders. They make behaviour reproducible, failures observable, and
experiments cheap.

## What "done" means

The finished deliverable is an **executable, evaluated reference** that shows how these
boundaries cooperate: a reproducible scenario suite (deterministic tests + a measured
live-model eval) and explicit architectural boundaries. Real commerce APIs and extra
channels can replace adapters later **without being prerequisites for completion**.

## The background idea

The commerce **nouns** are borrowed from [YAS](https://github.com/nashtech-garage/yas)
(a 25-microservice e-commerce sample): Product, Inventory, Order, Payment, Shipping,
Customer, Promotion. We take the **nouns**, never the infrastructure — those nouns become
**ports** with deterministic in-memory implementations, not services.

## What this is NOT

- Not a fork or clone of YAS.
- Not a microservices exercise; no HTTP between "services". The only process boundaries
  are the conversation edge (CLI + HTTP) and an optional MCP server.
- Not a frontend project — no web UI (an HTTP API is allowed, a UI is not).
- Not a "feature checklist". Specialists, RAG and memory must earn their place through an
  eval, not be added to look complete.
