# OSS — Development and Pull Request Gates

This document is mandatory for every code, dependency, architecture, and user-facing UI pull request.

## 1. Normative source of truth

Before implementation, read the relevant parts of:

1. [Product Specification](01-PRODUCT-SPECIFICATION.md)
2. [Technical Architecture](02-TECHNICAL-ARCHITECTURE.md)
3. [Engineering Plan](03-ENGINEERING-PLAN.md)
4. [Security and License Policy](04-SECURITY-LICENSE-POLICY.md)
5. [UI/UX System](05-UI-UX-SYSTEM.md)
6. [Performance and Quality](06-PERFORMANCE-QUALITY.md)
7. [Project Data Model](07-PROJECT-DATA-MODEL.md)
8. [Third-Party Component Audit](09-THIRD-PARTY-COMPONENT-AUDIT.md)
9. [Logging and Diagnostics](10-LOGGING-DIAGNOSTICS.md)
10. [Product Decision Register](12-PRODUCT-DECISION-REGISTER.md)

The README is an index, not a replacement for these requirements. The decision register identifies approved product behavior. Technical audit notes and prototypes do not override product decisions.

## 2. Required PR description

Every PR must include:

- **Purpose:** user-visible or technical outcome.
- **Requirement traceability:** exact decision/requirement IDs (for example S-002, S-003, S-005).
- **Scope:** files/subsystems changed and what is intentionally not changed.
- **Architecture impact:** data model, lifecycle, threading, rendering, audio, streaming, permissions, and dependencies as relevant.
- **Risk and rollback:** likely regressions and how to revert or disable the change.
- **Tests:** unit, instrumentation/emulator, and physical-device validation required.
- **Documentation:** which documents changed, or why no documentation change is required.

A PR that introduces a new product behavior without a decision ID must first propose and obtain approval for that decision in a documentation-only change.

## 3. Mandatory checks before merge

- [ ] The change matches the approved product decisions.
- [ ] No conflicting UI, data-model, or architecture behavior is introduced.
- [ ] Data migrations are explicit, transactional, and recoverable.
- [ ] LIVE-critical resources have deterministic startup, failure, and teardown paths.
- [ ] Optional source/module failures are isolated from Core and healthy LIVE destinations.
- [ ] Credentials and private user content are not logged or exported.
- [ ] Dependencies have exact versions, license/provenance review, and compatibility evidence.
- [ ] Structured diagnostics and stable error codes are added for new stateful operations.
- [ ] Tests cover the changed behavior and meaningful regressions.
- [ ] GitHub Actions build, unit tests, and required emulator matrix jobs all pass.
- [ ] Physical-device checks are scheduled only when the change touches behavior that emulators cannot validate.
- [ ] User-facing instructions are concise and requested only at a meaningful test milestone.

## 4. Scene and canvas specific checks

Any change touching canvas, scene geometry, editor orientation, or encoder output must explicitly verify:

- [ ] Only Portrait 9:16 and Landscape 16:9 are available in the initial release.
- [ ] LIVE output orientation is the source of truth for all scene canvases.
- [ ] No per-scene custom dimensions or extra aspect-ratio presets are exposed.
- [ ] Share Screen remains a user-added source element, never the canvas itself.
- [ ] Editor viewport zoom/pan/rotation do not alter scene-space geometry.
- [ ] Orientation conversion preserves proportions and does not stretch scene elements.
- [ ] Encoder output aspect ratio matches the selected LIVE orientation.
- [ ] Orientation cannot change underneath an active LIVE encoder session.

## 5. Audio specific checks

- [ ] Mic/internal/per-app sources remain separately identified where the platform supports it.
- [ ] Unavailable capture paths are reported honestly; no silent source substitution.
- [ ] LIVE and MONITOR routing remain distinct.
- [ ] Audio failure cannot block or terminate video processing.
- [ ] Tests are targeted to changed behavior; previously passed tests are not repeated without regression justification.

## 6. Merge policy

- Keep feature PRs in draft until implementation and tests are ready.
- Never merge with a failing or still-running required CI job.
- Review the actual diff, not only the PR title or summary.
- Merge documentation changes before dependent feature work.
- If code is found to conflict with the approved baseline, stop feature work and open a corrective PR.
- Do not claim a feature is complete based solely on compilation or emulator startup.

## 7. Definition of done

A feature is complete only when:
1. Its behavior matches an approved requirement.
2. Its UI and error states are usable.
3. Persistence and lifecycle behavior are correct.
4. Relevant tests pass.
5. Required physical-device validation has passed or is explicitly marked as a remaining release blocker.
6. Documentation and diagnostics reflect the actual implementation.
