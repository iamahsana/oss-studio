# OSS — Current Implementation Baseline Audit

Audit date: 2026-11-10  
Repository baseline inspected: `main` at the time of review, including merged PR #12.  
Purpose: compare the current source tree with the normative product requirements. This is an implementation-gap record, not approval of the current behavior.

## Executive result

The repository contains a substantial written product and architecture plan, but the current canvas implementation does not fully follow it. The most important mismatch is that scene canvas dimensions currently determine encoder output, while the product decision is that LIVE output orientation must determine the canvas.

**Do not continue adding canvas features until the orientation source-of-truth correction is implemented and verified.**

## Source-level findings

| Area | Current source evidence | Audit result |
|---|---|---|
| Canvas model | `SceneProject.kt` defines `CanvasSize` with width/height bounds and constants for Portrait, Landscape, Square, 4:5, 3:4, and 4:3. | Conflicts with S-002. |
| Canvas editor UI | `SceneEditorScreen.kt` contains custom width/height state, a canvas settings dialog, and canvas dimension display. | Conflicts with S-002 and S-003. |
| LIVE output size | `AndroidLiveRuntimeFactory.kt` calls `sceneOutputResolution(scene.canvas)`; `SceneOutputResolution.kt` scales the supplied scene canvas. | Scene canvas currently determines encoder aspect ratio; LIVE orientation is not independently authoritative. |
| LIVE orientation setting | The inspected `StudioLiveControlScreen.kt`, `AppSettings.kt`, and `SettingsScreen.kt` do not expose a persisted LIVE orientation selector. | Required setting/source-of-truth is missing. |
| Scene update during LIVE | `AndroidLiveRuntimeFactory.kt` logs that changing canvas dimensions requires restarting LIVE and ignores that canvas change in the active runtime. | Consistent with locking geometry during a session, but the global orientation-setting behavior still needs implementation. |
| Share Screen | `AndroidLiveRuntimeFactory.kt` references `ShareScreenLayerController`, `ShareScreenSessionService`, and the shared GL interface; the scene runtime treats Share Screen as a scene element and positions layers relative to its z-index. | The intended source/element architecture is present in code. Physical-device proof of final composited RTMP output remains a separate acceptance gate. |
| LIVE preview | Product and architecture documents exclude a duplicate preview renderer from the initial release. | Preserve this requirement; do not add a second renderer as a shortcut. |
| Center guides | PR #13 adds editor-only center guides and a smoke test; the PR remains draft while the documentation baseline is reconciled. | No LIVE geometry change intended; do not merge before the normative baseline is accepted. |

## Required corrective implementation sequence

1. Add a persisted LIVE output orientation setting with exactly two values: Portrait (9:16) and Landscape (16:9).
2. Make that setting the authoritative source for every scene canvas in the active project. Use canonical logical dimensions 1080×1920 and 1920×1080 respectively.
3. Implement an explicit, tested project-wide geometry conversion for orientation changes. Preserve relative layout, layer order, crop, rotation, and group relationships without stretching. Clamp only when needed. Provide undo and last-known-good recovery.
4. Allow orientation changes only while LIVE is stopped. The UI must clearly explain that all scenes in the active project will be converted.
5. Remove the Scene Editor's arbitrary ratio presets and custom width/height controls. Remove unsupported ratio constants from the product-facing model/API once migration compatibility is handled.
6. Make encoder output resolution derive from the LIVE orientation setting or its synchronized canonical canvas. The encoder must never infer output aspect ratio from editor viewport dimensions.
7. Add unit and instrumentation coverage for orientation persistence, multi-scene synchronization, geometry conversion, undo/recovery, UI options, and portrait/landscape encoder output.
8. Run CI before asking for physical-device testing. Request one focused device validation milestone for actual LIVE orientation/output and Share Screen compositing.

## Migration and compatibility safeguards

- Existing projects may contain 1:1, 4:5, 3:4, 4:3, or arbitrary custom dimensions from merged PR #12.
- Do not silently reinterpret these dimensions or discard scene geometry.
- Define and test a migration policy before removing the old model fields/constants. Preserve the original project snapshot for recovery.
- If legacy project dimensions cannot be mapped without destructive layout changes, retain a compatibility migration path and make the conversion explicit to the user.
- Keep project schema changes transactional and versioned.

## PR and branch status at audit time

- PR #12 (custom dimensions and ratio presets) is merged into `main`. It is a known deviation from the approved product direction and requires a corrective implementation PR; the merge itself does not change S-002 or S-003.
- PR #13 (center alignment guides) remains a draft and is held until this documentation baseline is reviewed.
- No feature PR should be merged ahead of the documentation baseline and the canvas correction plan.

## Additional product requirements confirmed after the source audit

The Scene Editor target is PixelLab functional/workflow parity, including the complete user-facing feature set; the current brief list of editor capabilities is a minimum and must be expanded into a feature-by-feature parity matrix before completion. Every user-addable scene element/source type (Text, Image, Shape, Sticker, Emoji, Drawing, GIF, Video, Template, Share Screen, and future source types) must be delivered as an extension. Core retains editor manipulation tools, extension hosting contracts, scene graph, and compositor. Production extension payloads download only on user demand after confirmation; beta builds may bundle only the test-required extensions. Preview remains deferred with no design or implementation work until the user explicitly revisits it.

These requirements are normative and must be applied to subsequent corrective implementation planning. The existing source audit above remains a point-in-time record of the inspected code and does not claim these requirements are implemented.

## PixelLab parity and extension implementation gap audit

The feature acceptance inventory is now maintained in [PixelLab Feature Parity Matrix](15-PIXELLAB-FEATURE-PARITY.md). A second source inspection against the current `main` branch confirms that the existing editor is an early prototype, not a PixelLab-parity editor.

| Requirement area | Current source evidence | Audit result |
|---|---|---|
| Editor workflow/layout | `SceneEditorScreen.kt` uses a vertically stacked Compose layout with canvas, add-element buttons, layer/properties panels, and simple sliders. | Does not yet reproduce PixelLab's complete screen/tool workflow or contextual editing system. |
| Existing element types | `SceneSourceType` currently defines TEXT, IMAGE, GIF, VIDEO, SHARE_SCREEN, CAMERA, USB_VIDEO. | Sticker, Emoji, Shape, Drawing, Template, and future element types are absent from the model. |
| Text editing | Current UI supports text content, font size, three fixed colors, left/center/right alignment, bold, and italic. | Missing the PixelLab parity set: broad/custom fonts, gradients/textures, curved/circular/3D text, letter/line spacing, stroke, shadow/inner shadow, reflection, emboss, mask, and other current text effects. |
| Image editing | Current source import accepts image/video; media elements expose crop sliders. | No verified perspective/warp, background removal, complete image filters/effects, image stroke/shadow, or full image property workflow. |
| Shapes/stickers/drawing | No corresponding source types or insertion flows in the inspected scene model/editor. | Missing. |
| Canvas/background | Scene model stores a single `backgroundArgb`; canvas dialog still exposes custom dimensions in the editor. | Missing gradient/image background workflows and conflicts with the approved LIVE-orientation-only canvas rule. |
| Object manipulation | Direct drag, four corner resize handles, grid/snap, safe-area guide, opacity, crop sliders, rotation property slider/buttons, duplicate, lock, visibility, delete, grouping, and undo/redo are present in some form. | Useful prototype foundation, but direct rotation handle, full contextual panels, multi-object editing parity, alignment/distribution, and complete PixelLab interaction behavior are not demonstrated. |
| Layer workflow | Layer list, multi-select, grouping, z-order controls and visibility/lock actions exist. | Partial; verify full reorder, naming, group manipulation, multi-selection actions, and PixelLab workflow details against the current app. |
| Extension delivery | `OssExtensionCenterScreen.kt` supports manually selecting a local `.osspack` file, listing installed packages, and removing them. `OssPackInstaller.kt` validates/extracts declarative resources. | There is no verified online/community catalog, metadata discovery, per-element install prompt, or integration from the editor's element shelf to the extension registry. |
| Element/extension binding | `SceneSourceType` and `SceneSource` serialize built-in enum values and settings; inspected project JSON has no extension ID/version requirement per source. | User-addable elements are still represented as built-in source types, not extension-provided element definitions. Missing extension placeholders and compatibility/version binding are absent. |
| Download policy | Extension Center currently accepts a user-picked local archive. | Demand-driven user confirmation is not yet implemented as an editor-to-catalog install workflow. No silent catalog download was observed, but required behavior is also not implemented. |
| Project and export workflows | Project persistence/autosave and media import are present. | PixelLab-style template/meme/quote discovery, editable project interchange, and image export/share parity are not verified in the inspected editor flow. |

### Required order after documentation baseline acceptance

1. Correct LIVE orientation as the sole source of truth, including transactional multi-scene migration and legacy custom-canvas recovery.
2. Establish the declarative element-extension contract and schema migration so every scene element stores extension identity/version and can survive a missing extension.
3. Connect Extension Center discovery/installation to the Scene Editor shelf, with user confirmation before every production download. Keep beta bundles limited to test-required extensions.
4. Build the PixelLab-parity editor incrementally against document 15, starting with the shared object model, selection/transform/layer history, and contextual tool system.
5. Add element extensions and editing capabilities in parity-matrix order. Do not declare the editor complete while any required feature remains unimplemented.
6. Keep preview deferred throughout this work.

The current editor is not close to complete PixelLab functional parity yet. This is a gap statement, not a criticism of the existing prototype; the existing canvas, project persistence, transforms, layer operations, and share-screen compositor integration can be retained where they meet the approved architecture.

## Audit limitations

This review confirms source-level wiring and UI/model behavior. It does not prove:
- stable RTMP output on physical devices;
- MediaProjection behavior across Android versions and OEM firmware;
- correct audio routing on the target phone;
- multi-destination throughput and failure isolation under real network conditions;
- long-session thermal, battery, and memory behavior.

These remain explicit validation gates, not assumed capabilities.
