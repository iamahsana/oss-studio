# OSS — Engineering Plan

## Stage 0 — Repository and engineering baseline
- Establish product/architecture decisions and license policy.
- Treat the Product Specification, Technical Architecture, UI/UX System, Project Data Model, Decision Register, and Development Gates as normative requirements. Reconcile conflicts before implementation; do not infer new product behavior from an isolated PR or code convenience.
- Every implementation PR must identify the relevant requirement/decision IDs, explain deviations, and include acceptance tests. A PR that conflicts with the normative documents must update the documents first and receive explicit product approval.
- Add Android project skeleton, version catalog, formatting/lint conventions, and CI.
- Keep build reproducible from GitHub Actions.
- Preserve a stable application ID and signing configuration strategy before distributing updateable APKs.

## Stage 1 — App foundation and usable UI
- Compose design system: typography, spacing, shapes, components, light/dark themes, configurable accent colors.
- Navigation and six main areas: Home, Studio, Scene Editor, Audio Mixer, Extension Center, Settings.
- Room/DataStore persistence, project model, autosave, recovery.
- Persist the LIVE output orientation preference/configuration so the Scene Editor and encoder can use one authoritative setting.
- Guest/debug account architecture; no repeated login during debug testing.
- UI must be usable and coherent, not placeholder-only.

## Stage 2 — Scene and media engine
- Scene/source registry and project persistence.
- Canvas orientation follows the LIVE output setting and supports Portrait (9:16) or Landscape (16:9) only; all scenes in the active project remain synchronized. No custom dimensions or extra aspect-ratio presets. Add layers, transforms, crop, grid/snap, and safe areas.
- Implement the PixelLab-parity Scene Editor interaction framework and build a traceable feature-by-feature parity matrix; do not mark the editor complete while any agreed PixelLab feature is missing.
- Implement the extension host/registry contracts required for all user-addable scene elements. Text, image, shape, GIF, video, Share Screen, and future source types are delivered as extensions, not permanently installed production Core elements.
- Asset manager, undo/redo, autosave/recovery.
- Share Screen as an optional source added to a scene; it is never the canvas itself.
- Bounded decoding/cache and lifecycle cleanup.

## Stage 3 — Streaming engine
- Encoder and RTMP/RTMPS adapter.
- Destination manager with independent state/reconnect/error handling.
- Multi-destination preflight and compatible encoder sharing.
- LIVE session lifecycle survives navigation and scene changes.
- Studio controls and useful diagnostics.

## Stage 4 — Audio engine
- PCM source pipeline and core mixer.
- Microphone and supported internal/per-app capture.
- Media-source and USB audio adapters where supported.
- Per-source meters, gain, mute, balance, LIVE/MONITOR routing.
- Essential integration tests only; avoid repeating already-passed historical tests without a regression reason.

## Stage 5 — Extensions and sharing
- Extension Center and declarative module manifest.
- Package every user-addable scene element/source type as an installable extension; download only on explicit user demand after confirmation.
- Beta/testing builds may bundle only the extensions needed for the test matrix; production installs must not silently bulk-download or bundle the full element catalog.
- Permission/compatibility/provenance validation.
- Core event bus and Koneksi integration contracts.
- Song Request, alerts, effects, and automation as separate modules subject to platform access.
- `.osspack` export/import with media assets and security validation.

## Stage 6 — Account, premium, and release hardening
- Select authentication and billing providers.
- Persistent sessions and entitlement restoration.
- Production/debug separation and credential security.
- Device benchmarks, long-session stability, thermal and battery tests.
- License notices, source attributions, privacy disclosures, release checklist.

## Acceptance gates
Every stage must pass:
0. Requirements traceability: the implementation matches the current normative docs and approved decision register; no undocumented product behavior is introduced.
1. Build succeeds in CI.
2. App launches without crash on the target device.
3. No regression to completed critical capabilities.
4. Resource use is measured rather than assumed.
5. User-facing UI for the stage is complete enough to test.

## Phone-only workflow
- GitHub repository and Actions are the source of truth.
- APKs are downloaded from workflow artifacts/releases.
- Build signing key must be preserved securely to allow in-place upgrades.
- Testing instructions must be concise and executable on Android.
