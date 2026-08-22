---
summary: Catalogue of Nodera's skills — what each one governs and when to load it. Skills are loaded on demand, never permanently in context.
read_when:
  - At session start, to decide which skills the work in front of you needs.
  - When adding a skill, so it is catalogued rather than merely present.
---

# Skills — Nodera

A **skill** is domain knowledge a contributor loads for a specific kind of work. Skills are not
permanently in context: load the ones the ticket routes to, plus
[`critical-invariants.md`](critical-invariants.md), which is loaded for **every** change.

Skills describe *how to do the work correctly*. Process rules live in
[`../docs/PROJECT_MANAGEMENT.md`](../docs/PROJECT_MANAGEMENT.md); product truth lives in
[`../docs/VISION.md`](../docs/VISION.md) and [`../docs/DOMAIN_MODEL.md`](../docs/DOMAIN_MODEL.md).

## Catalogue

| Skill | Load before | Governs |
|---|---|---|
| [`critical-invariants.md`](critical-invariants.md) | **Every change** | The twelve hard rules. A violation is a BLOCKING finding. |
| [`agent-actors.md`](agent-actors.md) | Identity, permissions, assignment, comments, audit | How to build for two kinds of participant without demoting one of them. |
| [`backend-kotlin.md`](backend-kotlin.md) | Any change under `backend/` | Module boundaries, `ActorContext`, error modelling, transactions. |
| [`frontend-react.md`](frontend-react.md) | Any change under `frontend/` | Mobile-first, the generated API layer, the component/hook boundary, rendering actor kind, accessibility floor. |
| [`design-system.md`](design-system.md) | Any view, component or styling | Exactly two themes, semantic tokens, touch ergonomics, and why agent output is never styled as second-class. |
| [`database-design.md`](database-design.md) | Any migration | Forward-only, identifier casing, RLS, indexing, where an invariant belongs. |
| [`mcp-integration.md`](mcp-integration.md) | Any change under `backend/api-mcp/` | Tool definition, capability declaration, idempotency, parity testing. |
| [`secure-coding.md`](secure-coding.md) | Auth, tokens, input, anything on the network | Fail closed, credential handling, permission placement, paired-negative tests. |
| [`testing.md`](testing.md) | Writing or reviewing tests | What proves something, and which tests are worse than none. |
| [`code-review.md`](code-review.md) | Every phase-4 review | The rubric, classification, evidence standard, scope governor. |

## Adding a skill

1. It earns its place by being **loaded before work**, not by being written down. Knowledge that
   belongs in a doc goes in a doc.
2. Frontmatter with `summary` and `read_when` is mandatory (`python scripts/docs_list.py`).
3. Add the row to the table above **and** to the skills index in
   [`../docs/INDEX.md`](../docs/INDEX.md), in the same commit — `python scripts/lint_docs_index.py`
   fails otherwise. Knowledge nobody can find is knowledge no tool has.
4. Keep it short enough to be re-read. A skill nobody re-reads is a document, not a skill.
