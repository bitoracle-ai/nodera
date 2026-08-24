---
id: OPS-02
title: Prove the release package by cutting one
priority: P2
status: open
effort: ~0.5 d
depends_on: []
created: 2026-08-20
updated: 2026-08-24
note: Carries the one OPS-01 criterion that cannot be proved from inside this repository.
---

# OPS-02 · Prove the release package by cutting one

**Priority:** P2
**Effort:** ~0.5 d

## Motivation / context

OPS-01 built the release path and could not prove it. Three independent review rounds recorded the
same gap: the workflow is configured for a two-architecture image with provenance, an SBOM and a
keyless signature, but **no release has ever run**, so none of it has been produced. Configured is
not produced, and a supply-chain guarantee nobody has exercised is a guarantee in the same sense a
backup nobody has restored is a backup.

This ticket exists because that proof cannot come from inside the repository. It needs GitHub
Actions, the `ghcr.io` registry, Sigstore's OIDC issuer, and a deliberate decision to publish a
version. That is `docs/PROJECT_MANAGEMENT.md` § 8's "external dependency" criterion, and it is the
only reason this is a ticket rather than a line in OPS-01.

## Current state (honest)

`.github/workflows/release.yml` is `workflow_dispatch`-only — enforced by
`scripts/lint_workflow_triggers.py`, not by a comment — and has never been dispatched. It builds for
`linux/amd64,linux/arm64`, passes `provenance: true` and `sbom: true`, signs the digest with keyless
cosign, and since OPS-01 verifies its own signature immediately afterwards with the same command an
operator would use. `compose.prod.yml` ships with the release.

What has actually been exercised: a single-architecture `amd64` image, built locally and checked by
`scripts/verify_image.sh` (14 checks). Nothing else. In particular no image has ever been pushed, no
attestation has ever been generated, and `cosign verify` has never run against a real signature.

**The premise moved after this ticket was written.** OPS-01 held `sigstore/cosign-installer` at v3
precisely so the release path would not meet a new cosign major untested, and `docs/ci.md` recorded
that pin as a decision waiting for this ticket. [#21](https://github.com/bitoracle-ai/nodera/pull/21)
merged the v4 bump anyway, and v4 defaults to `cosign-release: v3.0.6`. The first release will
therefore sign with **cosign 3**: the new protobuf bundle format and container signatures stored as
OCI Image 1.1 referring artifacts, both on by default. The sign and verify steps are unchanged and
pass no format flags, and both run the same CLI on the same runner, so the in-workflow verification
should hold. The operator half is the open question, and it is the half this ticket exists to prove.
cosign gained the bundle format and referring-artifact storage in **2.6.0**, behind
`--new-bundle-format`; cosign 3 only made them the default. So a verifier older than 2.6.0 cannot
read the signature at all, a 2.6.x one reads it only when passed `--new-bundle-format=true`, and 3.x
reads it unaided. There is no published `cosign verify` command anywhere in the tree yet — the
release notes are generated from `CHANGELOG.md` — so this ticket writes the first one, and it has to
carry a minimum version with it.

Note also that the version this would release is a build chain and two health endpoints. There is no
application yet. Cutting `0.1.0` is a decision about what a version number means here, not only a
test of the pipeline — see § "To decide before starting".

## Approach

1. Dispatch the workflow at a pre-release version, so the first exercise of the pipeline is not also
   the first thing a stranger might install.
2. Confirm each artefact rather than the workflow's exit code: two architectures in the manifest
   list, a provenance attestation, an SBOM, and a signature.
3. Run `cosign verify` from a clean machine — not the runner that made the signature. A verification
   that only passes where it was produced has proved the least interesting half.
4. Pull the published image on `arm64` and run `scripts/verify_image.sh` against it. The multi-arch
   claim is about the image working there, not about the manifest listing it.
5. Write down what an operator has to type, in `docs/DEPLOYMENT.md` (DOC-01) or, if that has not
   landed yet, in the release notes.

## ⚠️ To decide before starting

- **Which version.** Recommendation: `0.1.0-rc.1`. The release workflow already accepts a SemVer
  pre-release, `CHANGELOG.md` needs a matching section either way, and a pre-release says plainly
  that this is a pipeline exercise rather than something to run. `0.1.0` proper belongs to the first
  version with a domain in it.

## Acceptance criteria

- [ ] The workflow ran, and the run is linked from this ticket.
- [ ] `docker manifest inspect` shows both `linux/amd64` and `linux/arm64`.
- [ ] Provenance and SBOM attestations exist on the published digest and are readable.
- [ ] `cosign verify` accepts the signature **on a machine that did not produce it**, with the
      certificate identity and OIDC issuer pinned, and the exact command is recorded here.
- [ ] `scripts/verify_image.sh` passes against the **published** image pulled on arm64.
- [ ] A tampered digest is **rejected** by the same `cosign verify` command — the paired negative,
      without which the acceptance above only proves the command runs.
- [ ] The signature format the run actually produced is recorded here, and the `cosign verify`
      command published to operators names the minimum cosign version that can read it.
- [ ] `make check` green.
- [ ] Independent review (phase 4, run in a sub-agent): 0 BLOCKING findings.

## Affected files

- `CHANGELOG.md` — the section the workflow refuses to release without.
- This ticket — the run link, the digest, and the verification commands as run.
- Possibly `.github/workflows/release.yml`, if the run finds something the configuration got wrong.

## Verification

The artefacts themselves, inspected on a machine other than the runner. "The workflow went green" is
not verification of a supply-chain claim; it is verification that a script exited zero.
