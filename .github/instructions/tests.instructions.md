---
applyTo: "**/test/**"
---

# Tests — path-specific rules

- A safety claim ("this guard prevents X") ships with a PAIRED-NEGATIVE test that is demonstrably red
  when the guard is disabled. Without it the claim is an assertion, not a guarantee.
- Permission and RLS behaviour is tested against a real Postgres (Testcontainers), never a substitute.
- Anything a test needs beyond the repository's toolchain — a database, a service, the stack — runs in
  an environment created for that run: Testcontainers, or a compose project under its own `-p <name>`.
  Never the developer's `make up` stack or the volume behind it, and what the run created is removed
  afterwards. `make verify-db` is the documented exception (`skills/testing.md`).
- Anything enforced on both surfaces gets a parity test that drives the same denial through REST and
  through MCP.
- Test names are English sentences describing the behaviour, not the method under test.
