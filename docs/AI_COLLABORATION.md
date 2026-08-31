---
summary: The tool-neutral working contract for humans and AI assistants contributing to Nodera — minimum capabilities a tool must meet, entry points per role, the handoff format, and how to attach a new tool.
read_when:
  - On first entry into this repository with any AI assistant.
  - When it is unclear which sources and gates apply to the work in front of you.
  - Before permanently attaching another tool to the two-layer adapter architecture.
---

# AI collaboration — one project, free choice of tool

Nodera binds quality to **versioned project sources and executable gates**, not to a model vendor. Use
Claude Code, OpenAI Codex, GitHub Copilot, Cursor, OpenCode, an in-house harness or no assistant at
all. Native adapters are convenience; the canonical knowledge layer is plain Markdown that every tool
can read.

This project builds a system in which humans and agents are equal participants. It would be
incoherent to develop it in a way that only one vendor's tool can follow.

---

## 1. The shared minimum contract

A tool in use must be able to:

1. **Read files and cite paths and lines.** A claim about the code that cannot be traced to a location
   is not reviewable.
2. **Read [`INDEX.md`](INDEX.md)** and the sources it routes to, before making changes.
3. **Respect the root instructions and the applicable local ones** (`backend/AGENTS.md`,
   `frontend/AGENTS.md`, `db/migrations/AGENTS.md`).
4. **Produce changes as a verifiable diff** and run the documented gates — or deliver an honest,
   concrete verification handoff naming what was not run.
5. **Never copy credentials, private contact details or personal data** into prompts, logs, commits or
   tool configuration.
6. **Follow the five-phase workflow** ([`PROJECT_MANAGEMENT.md`](PROJECT_MANAGEMENT.md) § 3):
   overview → plan → implementation → independent review → findings.
7. **Write English into the repository**, whatever language the conversation runs in.
8. **Comment sparingly** — only at critical or genuinely complex spots, and lean even there.
   Rationale belongs in the ticket, the ADR or `docs/`, where it is indexed and can be corrected;
   a comment is none of those. **The existing tree is not the standard to imitate:** several
   migrations and Kotlin files carry paragraph-length commentary written before this rule, and a
   contributor who copies the surrounding style will be asked to trim it in review.

**A tool without a shell or an independent review instance** may still take on research, planning,
drafting and review preparation. It **may not mark work as finished** — the verifiable part is handed
to a human or to a tool that can actually run the gates. Claiming a green gate that was never run is
the most damaging thing an assistant can do here, and it is a BLOCKING finding wherever it is found.

## 2. Entry point by role

| Role | Read first | Typical work item | Done means |
|---|---|---|---|
| **Backend** | ticket → [`../skills/backend-kotlin.md`](../skills/backend-kotlin.md) → [`DOMAIN_MODEL.md`](DOMAIN_MODEL.md) | Domain service, use case, REST route | Gates green, unit tests for the invariant, independent review |
| **Frontend** | ticket → [`../skills/frontend-react.md`](../skills/frontend-react.md) → [`API_CONTRACT.md`](API_CONTRACT.md) | Component, view, data hook | Test file per unit, checked at 375 px, independent review |
| **Database** | ticket → [`../skills/database-design.md`](../skills/database-design.md) → [`DOMAIN_MODEL.md`](DOMAIN_MODEL.md) | Migration, RLS policy, index | Forward-only, applied on a scratch database, RLS proved by a negative test |
| **MCP** | ticket → [`MCP.md`](MCP.md) → [`../skills/mcp-integration.md`](../skills/mcp-integration.md) | Tool, resource, prompt | Capability declared and enforced, denial audited, parity test against REST |
| **Security** | ticket → [`../skills/secure-coding.md`](../skills/secure-coding.md) → [`DOMAIN_MODEL.md`](DOMAIN_MODEL.md) §§ 4, 8, 9 | Auth, tokens, permissions, audit | Paired-negative test that is demonstrably red with the guard disabled |
| **Review** | ticket → [`../skills/code-review.md`](../skills/code-review.md) → [`../skills/critical-invariants.md`](../skills/critical-invariants.md) | Phase-4 review | `APPROVED` only at 0 BLOCKING |

Anything touching identity, permissions or the audit trail additionally loads the mandatory sources
from [`INDEX.md`](INDEX.md) § Mandatory-reading rules. A role never lifts a scope-fence boundary.

## 3. Supported entry points

| Surface | Automatic entry | Local rules | Independent review |
|---|---|---|---|
| Claude Code | `CLAUDE.md` | local `CLAUDE.md` | `.claude/agents/reviewer.md` |
| OpenAI Codex | `AGENTS.md` | local `AGENTS.md` | `.codex/agents/reviewer.toml` |
| Copilot CLI / OpenCode / Cursor | `AGENTS.md` | local `AGENTS.md` | canonical review prompt in a sub-agent or a fresh chat |
| GitHub Copilot IDE/Chat | `.github/copilot-instructions.md` | `.github/instructions/*.instructions.md` | [`prompts/code-review.prompt.md`](prompts/code-review.prompt.md) in a separate chat |
| Any other tool | give [`INDEX.md`](INDEX.md) explicitly as context | give the matching local `AGENTS.md` | a separate human, agent or model with the canonical prompt |

"Supported" means the entry point is really in use and is checked by
`python scripts/lint_adapters.py`. Another tool is not excluded — a one-off user starts at the hub; on
repeated use, the tool earns a thin native adapter through its own work package.

**Attaching a new tool is one file** plus one entry in `ROOT_ENTRY_FILES` in `scripts/lint_adapters.py`.
No migration, no duplicated rules.

## 4. The independent review, concretely

Phase 4 is the load-bearing quality mechanism, and the rule is about context rather than calendars:
**the reviewing context did not write the code.**

- **Run it in a sub-agent.** The same session is fine; the same *context* is not. A sub-agent starts
  clean by construction, so impartiality is a property of the setup rather than something the reviewer
  has to remember. Continuing the implementation conversation and asking it to check its own work is
  not a review — that context produced the blind spots along with the code.
- A separate session, a different tool, a different model or a person all satisfy the rule as well.
  They are simply not required.
- Findings are classified **BLOCKING** or **NON-BLOCKING**, with the file and line.
- `APPROVED` is only returned at **0 BLOCKING**.
- A review that contradicts an earlier one is **recorded, not reconciled**. Both verdicts stay in the
  ticket. The contradiction is the most informative thing in the record.
- The reviewer verifies that the gates were actually run — not that the author said so.

Canonical, tool-neutral prompt: [`prompts/code-review.prompt.md`](prompts/code-review.prompt.md).

## 5. Cross-role handoff

Every handoff answers, compactly:

- Which problem and which acceptance criteria are authoritative?
- Which canonical sources and local guides were read?
- What was created or decided, with which deliberate non-goals?
- Which gates were run, with what result?
- What is **not** verified, why, and who takes over that verification?
- Which independent reviewer confirmed 0 BLOCKING?

A link to a chat transcript, a tool session or a screenshot is **not** a handoff. The versioned
artefact, the decision and the acceptance criteria must be findable in the repository by someone who
was not there.

## 6. Working with agents as contributors

Agents may contribute to Nodera under the same rules as anyone else. Two additions, both of which
exist because this project's subject matter makes them testable in practice:

1. **Attribution is not optional.** A pull request produced by an agent says so, and names the human
   accountable for it. This mirrors invariant A4 of the [domain model](DOMAIN_MODEL.md): the
   accountability chain terminates at a person.
2. **The agent's own claims are evidence, not conclusions.** "Tests pass" is a claim to verify, in
   exactly the way a human contributor's identical claim is. The review does not soften because the
   contributor is tireless.

## 7. Maintenance boundary

- Domain knowledge belongs in `docs/` or `skills/`.
- Native tool files contain loading hints and pointers only.
- Models, providers, personal paths and account settings are **not** pinned repository-wide unless they
  are a genuine project requirement.
- Vendor-specific recipes are optional adapters onto a tool-neutral workflow. They define neither the
  product truth nor the quality standard.
