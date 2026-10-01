# Vision

## Why this exists

Practicing **AI agents with Google ADK (Java)** in a realistic-but-small setting.
The point is not to ship a product; it is to answer, with evidence:

- What actually works when the model is a **small local model (4 GB GPU)**?
- How much do **tool calling, routing, and guardrails** improve outcomes?
- Where does a small model fall apart, and what is the cheapest fix?

The output of this repo is a **results table**, not a shop.

## The background idea

The domain is borrowed from [YAS](https://github.com/nashtech-garage/yas), a
Java microservices e-commerce sample. We take its **nouns** — Product, Cart,
Order, Inventory, Rating — and **none** of its infrastructure. There is no
Spring Boot, no Kafka, no Keycloak, no Kubernetes, no database server. The
"services" are in-memory fakes behind plain Java method calls.

## What success looks like

1. A working agent that can search a product catalog, manage a cart, and place
   an order **through tools**, on an OpenAI-compatible endpoint.
2. An `evals/` suite with a pass-rate table per model and per milestone.
3. A short writeup: what worked, what needed guardrails, what failed on small
   models.

## What this is NOT

- Not a fork or clone of YAS.
- Not a microservices exercise.
- Not a frontend project.
- Not a "feature checklist" agent (no RAG/multi-agent/memory until an observed
  failure justifies them).
