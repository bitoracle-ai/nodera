# Security policy

## Reporting a vulnerability

**Please do not open a public issue.** Use GitHub's private reporting:

**[Report a vulnerability →](https://github.com/bitoracle-ai/nodera/security/advisories/new)**

If that is unavailable to you, email **security@bitoracle.ai**. Encrypt if you prefer; ask
for a key in a first, contentless message.

### What to include

- What the vulnerability allows an attacker to do.
- The smallest reproduction you have. A reproduction is worth more than a description.
- Affected version or commit, and the surface (web, REST, MCP, database).
- Whether it is already public anywhere.

### What to expect

| | |
|---|---|
| Acknowledgement | within 3 working days |
| Initial assessment | within 10 working days |
| Fix or a dated plan | depends on severity; you will be told which, and when |
| Credit | offered by default in the advisory — say if you would rather not be named |

Nodera is **pre-alpha and not production-ready.** That is stated plainly because it changes
what a report means: there is no deployed instance to protect, and reports at this stage are
most valuable as design findings against the specifications in `docs/`.

## Scope

**In scope** — anything that breaks one of these:

- **Cross-project data access.** One project's rows reaching another actor. Row-level
  security is the boundary; a bypass is the most serious class of bug this project has.
- **Privilege escalation.** An actor obtaining a capability it was not granted — especially
  an agent exceeding its grantor, which is the failure the permission model exists to prevent.
- **Audit falsification.** Any path that updates, deletes or omits an `audit_event` row, or
  that attributes an action to the wrong actor.
- **Credential exposure.** A token in a log, an error, an API response, or recoverable from
  the database.
- **Authentication bypass** on any surface, including MCP.
- **Injection** — SQL, command, or stored markup rendered unsanitised.

**Out of scope:**

- Findings against a deliberately unimplemented feature. Check `tickets/INDEX.md` first.
- Missing hardening headers on a development server.
- Automated scanner output with no demonstrated impact.
- Social engineering, physical access, or denial of service by brute volume.
- Anything requiring an already-compromised host or an already-stolen valid credential.

## Reporting from an AI agent

Agent-produced reports are welcome and are read on the same terms as any other: a report is
judged on its reproduction, not its author. Please say that an agent produced it and name the
accountable human — the same accountability chain the product itself is built on.

An unverified scanner dump is not a report. Reproduce it first.

## Security design

Nodera's security model is documented rather than implicit, which also means it is
falsifiable. If one of these claims does not hold, that is exactly what we want to hear about:

- [`docs/DOMAIN_MODEL.md`](docs/DOMAIN_MODEL.md) §§ 4, 8, 9 — permissions, credentials, audit.
- [`skills/critical-invariants.md`](skills/critical-invariants.md) — the twelve hard rules.
- [`skills/secure-coding.md`](skills/secure-coding.md) — the implementation rules.
