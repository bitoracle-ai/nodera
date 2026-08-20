#!/usr/bin/env python3
"""ci_gate.py — aggregate GitHub Actions job results into ONE required check.

The ``ci-gate`` job passes ``toJSON(needs)`` in ``$NEEDS_JSON``. Its display name is
**"CI Gate"**, and that is the single Required status check in this repository's branch
ruleset for ``main``.

Keeping the aggregation here rather than in the ruleset means adding a lane is a change
to ``needs:`` and to this file — nothing on GitHub has to be reconfigured, and the ruleset
never learns the job list. It also means a lane that is accidentally *skipped* cannot be
reported as a pass: a required job that did not succeed makes the gate red.

Contract:
  * every job in REQUIRED must be ``success``;
  * any other job (a deliberately conditional lane) must be ``success`` or ``skipped``;
  * any ``failure`` or ``cancelled`` makes the gate red;
  * a REQUIRED job absent from the map is itself a problem — it means the workflow and
    this file have drifted apart.

Usage:
    NEEDS_JSON='{"backend":{"result":"success"}}' python scripts/ci_gate.py
"""

from __future__ import annotations

import json
import os

REQUIRED: tuple[str, ...] = ("secret-scan", "repo-checks", "backend", "frontend", "database")
OK_RESULTS: frozenset[str] = frozenset({"success", "skipped"})


def evaluate(needs: dict[str, object]) -> tuple[bool, list[str]]:
    """Return ``(passed, problems)``, problems ordered by job name for a stable log."""
    problems: list[str] = []

    for job in REQUIRED:
        if job not in needs:
            problems.append(f"{job}=<absent> (required - missing from the job graph)")

    for job, meta in sorted(needs.items()):
        result = meta.get("result") if isinstance(meta, dict) else None
        if job in REQUIRED:
            if result != "success":
                problems.append(f"{job}={result} (required - must be success)")
        elif result not in OK_RESULTS:
            problems.append(f"{job}={result}")

    return (not problems), problems


def _result(meta: object) -> object:
    return meta.get("result") if isinstance(meta, dict) else None


def main() -> int:
    raw = os.environ.get("NEEDS_JSON", "").strip()
    if not raw:
        print("::error::ci-gate: NEEDS_JSON is empty - cannot evaluate job results")
        return 1
    try:
        needs = json.loads(raw)
    except json.JSONDecodeError as exc:
        print(f"::error::ci-gate: NEEDS_JSON is not valid JSON: {exc}")
        return 1
    if not isinstance(needs, dict):
        print("::error::ci-gate: NEEDS_JSON is not a job-to-result map")
        return 1

    passed, problems = evaluate(needs)
    print("Job results: " + ", ".join(f"{job}={_result(meta)}" for job, meta in sorted(needs.items())))
    if not passed:
        print("::error::ci-gate FAILED - " + "; ".join(problems))
        return 1
    print("ci-gate OK - every required job succeeded.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
