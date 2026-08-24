---
id: FIX-01
title: gradlew.bat entered the index as CRLF — renormalise and gate it
priority: P2
status: closed
effort: ~0.25 d
depends_on: []
created: 2026-08-23
updated: 2026-08-24
closed: 2026-08-24
note: Introduced by the #23 wrapper bump; caught while merging it, not by any lane.
---

# FIX-01 · gradlew.bat entered the index as CRLF — renormalise and gate it

**Priority:** P2
**Effort:** ~0.25 d
**Skills:** `critical-invariants.md`

## Motivation / context

`.gitattributes` says `gradlew.bat text eol=crlf`: LF in the repository, CRLF in the working tree.
Dependabot commits through the GitHub API, which writes blob bytes verbatim, so git's clean filter
never runs on what it pushes. PR #23 regenerated the wrapper and the new `backend/gradlew.bat` went
into the index with CRLF.

The consequence is not cosmetic. On checkout the file is written CRLF (correct), git cleans it back
to LF to compare, and that no longer matches the stored blob — so `backend/gradlew.bat` shows as
modified in a fresh clone before anyone has touched anything. Every lane was green: no gate looks at
how a blob is recorded, only at what it contains.

This is the same family as CI-01, which was also a wrapper file recorded wrongly in the index, also
invisible to every check, and also only found by someone reading the merge rather than the result.

## Current state (honest)

Fixed in this package. Before it:

```
$ git ls-files --eol | grep -v 'i/lf' | grep -v 'i/none'
i/-text w/-text attr/-text            	backend/gradle/wrapper/gradle-wrapper.jar
i/crlf  w/crlf  attr/text eol=crlf    	backend/gradlew.bat
```

The `.jar` is `-text` by `.gitattributes` and correct. `backend/gradlew.bat` was the only defect —
`backend/gradlew` itself survived #23 as `100755`, so the executable bit held.

## Approach

1. `git add --renormalize backend/gradlew.bat` — the blob becomes LF, the checkout stays CRLF.
2. `scripts/lint_line_endings.py`: fail on any tracked file whose **index** eol is `crlf` or
   `mixed`, read from `git ls-files --eol` rather than from the working tree, which is supposed to
   differ.
3. Wire it into `make check-repo` and the Repository-checks job, next to the executable-bit gate.
4. Record in `docs/ci.md` what a Dependabot bump gets past every lane, so the next wrapper bump is
   read rather than waved through.

## Acceptance criteria

- [x] `git ls-files --eol` reports no `i/crlf` or `i/mixed` entry.
- [x] `scripts/lint_line_endings.py` exits 0 on the fixed tree and **1** on the defect — the paired
      negative is run against the real CRLF blob, not a fabricated one, and recorded below.
- [x] The gate runs in `make check-repo` and in the `Repository checks` job.
- [x] `docs/ci.md` names the mechanism and the gate.
- [x] `make check-repo` green.
- [x] Independent review (phase 4, run in a sub-agent): 0 BLOCKING findings.

## Affected files

- `backend/gradlew.bat` — renormalised; the content does not change, only the recorded form.
- `scripts/lint_line_endings.py` — new.
- `Makefile`, `.github/workflows/ci.yml` — the gate, next to the executable-bit one.
- `scripts/_common.py`, `scripts/lint_executable_bits.py` — the git-subprocess wrapper the two
  index-recording gates would otherwise hold two drifting copies of, extracted to `git_output`.
- `docs/ci.md` — the mapping table, the step table, and the mechanism.

## Verification

Paired negative, run against the actual CRLF blob `8508ef6` from PR #23:

```
$ git update-index --cacheinfo 100644,8508ef684d4e1f8473dcbbfdacf52a131beaee0e,backend/gradlew.bat
$ git ls-files --eol backend/gradlew.bat
i/crlf  w/crlf  attr/text eol=crlf    	backend/gradlew.bat
$ py scripts/lint_line_endings.py
FAIL - 1 line-ending problem(s):
  - backend/gradlew.bat: stored with CRLF in the git index. .gitattributes normalises text to LF
    in the repository, so this file reads as modified in every fresh clone.
    Fix: git add --renormalize backend/gradlew.bat
exit=1

$ git add --renormalize backend/gradlew.bat
$ py scripts/lint_line_endings.py
OK - every tracked text file is stored LF in the index.
exit=0
```

The gate is red on the defect that actually happened and green once it is fixed.

## Review result

Two independent sub-agent rounds, each against the staged diff.

**Round 1** — 2 BLOCKING, 7 NON-BLOCKING across the package. None against FIX-01: the gate, its
wiring and its paired negative were reproduced independently, including that the negative used PR
#23's actual blob `8508ef6` rather than a fabricated one. One NON-BLOCKING here (N7): the git
subprocess wrapper duplicated `lint_executable_bits.py` line for line. Fixed by extracting
`git_output` into `scripts/_common.py`; both gates now use it.

**Round 2** — 1 BLOCKING, in OPS-03's files, none here. The reviewer re-derived the paired negative
after the extraction — rebuilt the CRLF blob in a throwaway repository, confirmed byte-identity by
SHA, and checked the gate fires on `i/crlf` and `i/mixed` with no false positive on `i/-text`,
`i/none` or `i/lf`. Index tree hash identical before and after its experiments.

Left deliberately undone, recorded rather than ticketed: `release.yml`'s `Repository gates` step
does not run this gate, though its own comment says it keeps level with `ci.yml`. Adding it means
editing the release workflow, which this package has no other reason to touch and which
`lint_workflow_triggers.py` guards; it belongs with OPS-02, the package that first dispatches a
release. Also noted: `_common.ignored_paths()` still hand-rolls its own `subprocess.run` with a
different timeout — the same drift the extraction addressed, one function away.
