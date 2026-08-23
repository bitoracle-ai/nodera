---
description: Run every local gate (all CI lanes except the CI-only gitleaks secret scan) and report honestly which passed
---

Run the local gates and report the result.

```
make check
```

If `make` is unavailable, run the lanes individually — see `docs/ci.md` for the job-to-local
mapping.

Report:

- Which gates ran and their result.
- **Which gates did NOT run, and why.** The backend tests need a running Docker daemon; if it is
  not available, say so rather than omitting the row.
- For each failure: the file, the line and what the gate is actually complaining about — not
  just the raw output.

Never report a gate as green that you did not run.
