---
description: Run every local gate (all CI lanes except the CI-only gitleaks secret scan and verify-db) and report honestly which passed
---

Run the local gates and report the result.

```
make check
```

`make check` does not apply the migrations — `make verify-db` does, and it is a separate target.
If `make` is unavailable, run the lanes individually — see `docs/ci.md` for the job-to-local
mapping.

Report:

- Which gates ran and their result — and which of them **executed** rather than being served from
  the cache or skipped as up-to-date. On an unchanged tree Gradle reports the backend lane green
  without running a test (`docs/ci.md`).
- **Which gates did NOT run, and why.** The backend tests need a running Docker daemon; if it is
  not available, say so rather than omitting the row.
- For each failure: the file, the line and what the gate is actually complaining about — not
  just the raw output.

Never report a gate as green that you did not run.
