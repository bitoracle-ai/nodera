# ADR-0002 — Provider-agnostic agent adapters: a two-layer architecture for AI tooling

- **Status:** Accepted (2026-08-20)
- **Context documents:** [`../INDEX.md`](../INDEX.md) · [`../AI_COLLABORATION.md`](../AI_COLLABORATION.md)
- **Affects:** `CLAUDE.md`, `AGENTS.md`, `.github/copilot-instructions.md` + `.github/instructions/`,
  the scoped guide pairs, `docs/INDEX.md`, `scripts/lint_adapters.py`.

## Context

Contributors are not one person with one tool. A contributor whose assistant never sees the session
protocol, the invariants or the scope fence produces work that has to be caught in review instead of
being right by construction — and in an open-source project the set of tools in use is not even
knowable in advance.

There is a second reason specific to this project: Nodera is built on the premise that no
participant is second-class because of which runtime it happens to use. Developing it in a way that
only one vendor's assistant can follow would contradict the product in its own repository.

**Forces:**

- **No rule drift:** the same rule must not be maintained in parallel across several tool files.
- **Consistency must not depend on discipline:** whatever can be checked mechanically is checked.
- **Windows reality:** symlinks between `CLAUDE.md` and `AGENTS.md` are fragile on a Windows
  development machine and are rejected.
- Tools appear and disappear. Docking one must cost approximately nothing.

## Decision

**Two layers.**

1. **Layer 2 — source of truth:** `docs/` and `skills/`, with the tool-neutral hub
   [`../INDEX.md`](../INDEX.md), the generated inventory `docs/docs_map.md`, and
   [`../prompts/README.md`](../prompts/README.md) as the **only** place reusable prompts live.
2. **Layer 1 — thin adapters,** one file per tool actually in use, carrying distillates and pointers
   only: `CLAUDE.md` (Claude Code) · `AGENTS.md` (the cross-tool standard read natively by Codex,
   Copilot CLI, OpenCode and others) · `.github/copilot-instructions.md` plus path-specific
   `.github/instructions/*.instructions.md` (Copilot IDE/Chat).

**Rules**, mechanically checked by `scripts/lint_adapters.py`: change a rule in layer 2 first, then
pull the distillates along in the same work package · root adapters never reference each other · a
new tool is one thin file plus one `ROOT_ENTRY_FILES` entry · scoped guides exist as
**content-identical** `CLAUDE.md`/`AGENTS.md` pairs (tracked files with an identity check, not
symlinks) · no `{{PLACEHOLDER}}` survivors · no repository-wide `applyTo`.

## Consequences

- ✅ Every supported tool starts with the same hard rules and finds the same source of truth.
- ✅ Docking a new tool is one file. No migration, no drift.
- ✅ Adapter consistency is checked mechanically rather than left to review attention.
- ⚠️ Scoped pairs duplicate bytes deliberately; identity is checked, so drift is impossible.
- ⚠️ **Distillates remain distillates.** Semantic drift — a rule changed in layer 2, the short form
  forgotten — is **not** caught by the linter. Nothing can catch it. That is what the
  same-work-package rule and [`../prompts/maintain-adapters.prompt.md`](../prompts/maintain-adapters.prompt.md)
  are for, and saying so is better than implying a coverage the gate does not have.

## Alternatives considered

- **One `CLAUDE.md` only:** rejected — it makes tool choice a quality variable, which is the failure
  this ADR exists to prevent.
- **Symlinked pairs:** rejected, Windows-fragile. Content-identical tracked files with an identity
  check are functionally equivalent and robust.
- **Adapters for tools nobody uses:** rejected. Dead config that cannot be verified against a real
  instance is drift surface without users, and docking is one file anyway.
- **Generating layer 1 from layer 2:** rejected for now. A generator would remove the semantic-drift
  risk, but a distillate is an editorial act — deciding what matters most for a given tool — and a
  generated one would either reproduce the whole document or make that decision badly.
