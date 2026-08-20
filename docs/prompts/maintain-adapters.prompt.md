# Adapter maintenance prompt

`scripts/lint_adapters.py` checks the mechanical half of the two-layer architecture: files exist,
they point at the hub, root adapters do not reference each other, no placeholders survive, scoped
pairs are identical.

It cannot see **semantic drift** — a rule changed in `docs/` or `skills/` while its short form in
`CLAUDE.md`, `AGENTS.md` or `.github/copilot-instructions.md` still says the old thing. No linter can.
This prompt is that check, run by a reader.

---

You are auditing Nodera's layer-1 tool adapters against layer 2.

**Layer 2 (source of truth):** `docs/` and `skills/`.
**Layer 1 (adapters):** `CLAUDE.md`, `AGENTS.md`, `.github/copilot-instructions.md`,
`.github/instructions/*.instructions.md`.

For **every rule stated in an adapter**, find its source in layer 2 and compare:

1. **Contradiction** — the adapter says something layer 2 no longer says. Report as DRIFT.
2. **Orphan** — the adapter states a rule with no layer-2 source at all. Report as ORPHAN: either the
   rule is real and belongs in layer 2, or it is someone's preference that acquired the authority of
   a written rule by being written down.
3. **Omission** — layer 2 carries a hard rule (an invariant, a boundary, a scope-fence line) that no
   adapter mentions. Report as GAP.
4. **Overreach** — the adapter reproduces layer 2 at length instead of distilling. Report as BLOAT: a
   distillate that is as long as its source will not be re-read, and an adapter nobody re-reads is a
   file, not an instruction.

Report as:

```
DRIFT   <adapter>:<line> — says "<X>" — <layer-2 file> § <n> now says "<Y>"
ORPHAN  <adapter>:<line> — "<X>" — no source found in layer 2
GAP     <layer-2 file> § <n> — "<X>" — stated in no adapter
BLOAT   <adapter> § <n> — reproduces <layer-2 file> § <n> rather than distilling it
```

Do not fix anything. This audit produces findings; the fix happens in a work package, because
changing an entry file in passing is itself a violation of the rule you are auditing.
