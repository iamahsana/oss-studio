# OSS Studio — Comprehensive Correction Audit

Audit date: 2026-11-10
Baseline: `main` after documentation baseline merge `4d7b616`
Scope: source-level review against the normative product, architecture, engineering, data-model, UI, and PixelLab parity documents.

This audit records verified implementation gaps. It is not a claim that the listed corrections are already implemented. No feature expansion should proceed until the corrective gates below are met.

## Executive assessment

The repository is buildable and contains working foundations for RTMP streaming, Share Screen composition, audio mixing, project persistence, and a first editor prototype. However, several foundations still contradict the accepted product decisions. The current app is not yet at the agreed product architecture or PixelLab-parity editor state.

## Verified findings

| Severity | Area | Source evidence | Required correction |
|---|---|---|---|
| Blocker | LIVE orientation source of truth | `AppSettings` stores only theme and accent; `AndroidLiveRuntimeFactory.prepare()` calls `sceneOutputResolution(scene.canvas)`. | Persist exactly Portrait 9:16 / Landscape 16:9 in LIVE settings; make it authoritative for all active-project scenes and encoder output. |
| Blocker | Canvas model and editor controls | `Scene` stores its own `CanvasSize`; `CanvasSize` still exposes square, 4:5, 3:4, 4:3 and arbitrary dimensions; `SceneEditorScreen` still exposes custom width/height state and canvas settings. | Replace independent per-scene sizing with synchronized canonical dimensions; remove unsupported canvas controls only after migration is safe. |
| Blocker | Legacy project migration | `SceneProject.CURRENT_VERSION` is 1; `SceneProjectJsonCodec.decode()` accepts only an exact schema-version match; scene JSON requires stored width, height and orientation. | Add explicit, versioned, backwards-compatible decoding/migration. Preserve original documents and assets; never silently discard or reinterpret legacy geometry. |
| High | Recovery and transactional conversion | `SceneProjectDatabase` stores one prior document in `project_recovery`; `SceneProjectRepository` exposes ordinary save/load; no project-wide orientation conversion transaction is present in the inspected model. | Add an atomic project-wide migration operation, a last-known-good snapshot, failure rollback, and a user-accessible undo/recovery path. |
| Blocker | Extension-backed elements | `SceneSourceType` is a built-in enum; `SceneSource` serializes the enum name. The editor creates source types directly. | Introduce stable extension IDs, element type IDs, version constraints and extension-owned configuration. Missing extensions must preserve source data and show a recoverable placeholder. |
| High | Extension Center | `OssExtensionCenterScreen` only imports local `.osspack` files, lists installed packages and removes them. No editor shelf-to-extension install flow or catalog/update discovery was found in the inspected implementation. | Build catalog/discovery, compatibility/provenance display, explicit per-element download confirmation, update flow and safe removal semantics. |
| High | Extension capability coverage | `OssPackArchiveValidator` currently recognizes only `scene-overlay`, `media-source`, and `text-widget` capabilities. | Define declarative contracts for every required element family, with schema validation, versioning, permissions and runtime isolation. |
| Blocker | PixelLab parity | `SceneEditorScreen` remains a single Compose prototype with a vertical layout, limited text/media tools and simple contextual controls. | Implement the full feature matrix feature-by-feature: workspace, typography, effects, image tools, shapes, stickers, drawing, layers, groups, selection, crop, rotation, alignment, export and project workflows. Do not declare completion while required rows remain. |
| High | Navigation and page composition | `MainActivity.PageContent` composes page content inside one vertical `LazyColumn`; the editor is inserted as a list item rather than being a dedicated full-workspace screen. | Give Home, Studio, Editor, Mixer, Extensions and Settings proper page-level layouts. Keep the editor canvas dominant and LIVE session state global. |
| High | LIVE settings UI | `StudioLiveControlScreen` exposes start/stop, destination status and Share Screen readiness but no LIVE orientation selector. `SettingsScreen` has appearance, storage, diagnostics and about sections only. | Put orientation in the LIVE output settings area, explain project-wide conversion, and disable changes during an active session. |
| Medium | Audio capability mapping | The inspected mixer runtime initializes microphone and internal-audio channels; the UI exposes these two channels and separate LIVE/MONITOR master buses. | Preserve the already-passed core audio behavior. Separately verify optional per-app playback capture and expose it only for apps Android actually permits; do not regress the existing mixer. |
| Medium | Output resolution tests | `SceneOutputResolutionTest` verifies portrait, landscape and custom-canvas behavior. | Replace custom-ratio acceptance with tests driven by the persisted LIVE orientation and canonical 9:16 / 16:9 output sizes. |
| High | Test coverage for migration and extensions | The inspected tests cover current canvas/model invariants and output scaling; no codec migration test was found at the expected test path. | Add fixture-based schema migration, recovery/rollback, extension missing/version mismatch, element serialization and install-confirmation tests. |

## Corrective order

1. **Freeze feature additions.** Keep PR #13 (center guides) unmerged until it is reviewed against the corrected baseline.
2. **Protect existing projects first.** Add schema-versioned decoding and fixture tests for current project documents before changing model fields.
3. **Correct LIVE orientation.** Persist the two-value LIVE orientation; add deterministic project-wide geometry conversion; retain a recoverable pre-conversion snapshot; disable changes during LIVE; derive encoder dimensions from the same setting.
4. **Remove conflicting canvas UI/model behavior.** Remove custom ratios and dimensions only after legacy migration and recovery tests pass.
5. **Correct extension architecture.** Move all user-addable scene element implementations behind stable extension contracts; preserve unavailable elements and demand-download only after explicit confirmation.
6. **Rebuild the editor against the parity matrix.** Use original OSS implementation and assets; compare the current Android PixelLab UI and supplied references feature-by-feature. Preview remains deferred.
7. **Correct application page structure.** Separate the six main areas into dedicated layouts; do not let the editor be constrained by a generic page list.
8. **Preserve verified audio/streaming behavior.** Add only targeted regression tests; do not repeat historical audio tests without a regression.
9. **Run CI gates per corrective PR.** Only request a focused physical-device milestone after CI is green and the relevant stage is ready.

## Non-negotiable safeguards

- No production build silently bundles or downloads the full element catalog. Beta builds may bundle only the extensions required for that test.
- No orientation change while LIVE is active.
- No geometry stretching during migration; preserve relative layout, crop, rotation, layer order and group relationships as far as possible.
- No project data deletion when an extension is missing, removed or incompatible.
- No preview renderer or preview-related implementation while preview remains deferred.
- Do not merge a corrective PR solely because CI passes; review its data compatibility and product-decision compliance.
- Keep all OSS work in `iamahsana/oss-studio`; do not mix it with the separate ERP project.

## Completion definition

The correction phase is complete only when the blocker findings above have been addressed in code, the migration and extension acceptance tests pass, the PixelLab parity matrix is implemented or has explicit user-approved deviations, and the app's main pages match the agreed product structure. A written audit or green CI alone is not completion.
