# Changelog

All notable changes to Nodera are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The release workflow refuses to cut a version with no section here — a release nobody can
evaluate is not a release.

## [Unreleased]

### Added

- Repository foundation: vision and scope fence, domain model, architecture, MCP surface
  specification, and the baseline database schema as four forward-only migrations.
- The rule set: twelve critical invariants, nine skills, and the phase-4 review rubric.
- Tool-agnostic adapter layer (ADR-0002) with mechanical consistency checks, so a
  contributor's choice of AI assistant is not a quality variable.
- Markdown ticket system with generated views, plus the tooling that keeps them honest.
- CI with one aggregated required check, CodeQL analysis, and a manual-only release path
  enforced by a gate rather than by a comment.

### Notes

Nothing is released yet. The application itself is not implemented — see
[`tickets/INDEX.md`](tickets/INDEX.md) for what exists and what is next.
