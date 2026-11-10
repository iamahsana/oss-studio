# OSS Scene Editor — PixelLab Functional Parity Matrix

Status: normative acceptance specification. The target is PixelLab functional/workflow parity, not a loose visual reference. This document converts the user's explicit requirement into testable feature groups. It must be maintained against the current PixelLab Android application and the user's supplied screenshots/references.

## 1. Non-negotiable product rules

1. Scene Editor must reproduce PixelLab's editing experience, tool organization, object manipulation, contextual panels, and complete user-facing editing capabilities as closely as practical.
2. This is a functional requirement. A similar-looking canvas with only drag/resize is not acceptable.
3. OSS must use original implementation, original assets, OSS identity, and independently authored UI. Do not copy PixelLab source code, proprietary assets, branding, or protected artwork.
4. Adapt the editor's output to OSS scenes and LIVE composition. PixelLab's image/photo canvas does not override OSS's product rules: only Portrait 9:16 and Landscape 16:9 are allowed, selected in LIVE settings and applied to every scene in the active project.
5. Preview is deferred. Do not design, implement, or test a separate preview renderer until the user explicitly reopens that decision. The editor canvas is the composition workspace; the compositor output is LIVE.
6. Every user-addable element/source is an extension: Text, Image, Shape, Sticker/Emoji, Drawing, GIF, Video, Share Screen, templates and future source types. Core supplies the editor tools, scene graph, extension host, and compositor contracts.
7. Production downloads are demand-driven only. When the user selects an uninstalled element/extension, explain it and ask before downloading. Do not silently download the whole catalog. Beta/testing builds may bundle only the extensions needed for their test scenarios.
8. A missing extension must never erase a scene element. Preserve its data, transform, layer order, and assets and show a recoverable unavailable-element placeholder.

## 2. PixelLab public feature inventory and OSS mapping

The baseline inventory is derived from the official Google Play listing for PixelLab - Text on pictures (App Holdings, package `com.imaginstudio.imagetools.pixellab`):
https://play.google.com/store/apps/details?id=com.imaginstudio.imagetools.pixellab

The listing describes text and 3D text, text effects, gradients and image textures, 100+ fonts/custom fonts, stickers/emojis/shapes, image import, drawing, color/gradient/image backgrounds, project save/reopen, background removal, perspective editing, image effects, image export, meme presets, and quotes. The feature matrix below combines that public Android inventory with the user's previously agreed editor interactions. Features that appear only in other platform/version listings or third-party guides are marked for direct Android verification rather than assumed. This is a minimum inventory, not permission to omit features found in the actual installed PixelLab app. Before final acceptance, compare the complete current Android app UI, menus, and tool panels on-device and extend this matrix wherever additional capabilities are found.

### A. Workspace, document and project workflow

| Capability | OSS requirement | Delivery / acceptance |
|---|---|---|
| New project / blank canvas | Start a scene/project with an empty composition | Required; create and reopen test |
| Canvas workspace | Large dominant work area with PixelLab-like zoom/pan and touch handling | Required; viewport operations never change scene geometry |
| Canvas orientation | Portrait 9:16 or Landscape 16:9 only, driven by LIVE settings | Required; no square, custom, or additional ratio presets |
| Background | Solid color, gradient, or image background | Background element/config delivered through extension system; verify compositor output |
| Templates / presets | Browse, apply, and edit ready-made compositions, including relevant poster/story/social templates | Template packs are extensions; demand download |
| Project persistence | Save, reopen, duplicate, organize, and recover editable projects/scenes | Required; no flattened-only save |
| Quotes / insertable content | Browse and insert supported quote/text content where this PixelLab workflow is present | Content packs are extensions; download only on demand |
| Meme presets | Ready-made meme layouts and editable text/image placeholders | Template extension; preserve editable objects |
| Import/export project | Import and reopen editable project data; retain layer structure and element references | Required; schema/version validation and recovery |

### B. Text editing

| Capability | OSS requirement | Delivery / acceptance |
|---|---|---|
| Multiple text objects | Add and edit multiple independent text layers | Text extension; independent layer IDs |
| Plain text | Create, replace, and edit text with a touch-first editing flow | Required |
| 3D text | Depth/extrusion-style text with editable parameters | Text extension; render and reopen test |
| Curved / circular text | If present in the current PixelLab Android build, support text along a curve or circular path | Verify directly in the current Android app; if present, required parity feature |
| Font library | Broad font catalog comparable to PixelLab's published 100+ font selection | Font packs/extensions; not all fonts bundled |
| Custom fonts | Import supported user font files and use them in text objects | Font ingestion extension/service; validate format and licensing |
| Text alignment | Left, center, right and supported vertical alignment | Required |
| Letter and line spacing | Independently adjust tracking/word spacing and line spacing | Required |
| Text fill | Solid color, linear gradient, radial gradient, and image texture fill | Required |
| Text opacity | Adjustable transparency | Required |
| Text stroke / outline | Color, width, opacity and supported edge behavior | Required |
| Shadow | Color, blur, opacity, X/Y offset and related controls | Required |
| Inner shadow | Editable inner-shadow effect | Required |
| Background / highlight | Text background/fill panel and padding/corner behavior where available | Required |
| Reflection | Editable reflection effect | Required |
| Emboss | Editable emboss effect | Required |
| Mask | Text mask/clipping effect where supported by PixelLab | Required |
| Text transform | Move, resize, rotate, scale and position using direct handles and precise property controls | Required |
| Text conversion | Inspect the current Android build for text-to-shape/vector conversion and reproduce it if present | Current Android availability must be verified before marking required; do not infer from another platform/version |

### C. Images, stickers, shapes and drawing

| Capability | OSS requirement | Delivery / acceptance |
|---|---|---|
| Image import | Import user images from Android storage picker | Image element extension; retain managed asset reference |
| Stickers | Browse, add, recolor/transform where supported | Sticker packs are extensions; on-demand download |
| Emoji | Add and transform emoji elements | Emoji packs/extensions; on-demand download |
| Shapes | Add common geometric and decorative shapes; customize fill, outline, size and transform | Shape extension; do not restrict to a single rectangle |
| Drawing | Freehand draw with adjustable brush/pen size and color | Drawing extension; output remains an editable scene element |
| Drawing as object | Select, resize, rotate, position, and apply supported effects to a completed stroke | Required |
| Background removal | Remove/transparent-key image backgrounds, including supported green/blue/white background workflows | Image-tool extension; expose processing limits and preserve original asset |
| Image perspective / warp | Perspective transform for image content, with editable handles | Image-tool extension; required parity feature |
| Image effects | Vignette, stripes, hue, saturation and the complete currently available PixelLab effect inventory | Effect packs/extensions; non-destructive parameters |
| Image crop | Crop and reposition content independently of the element's outer transform | Required |
| Image stroke / shadow | Inspect current PixelLab Android image tools and reproduce any available outline/shadow controls | Verify exact Android availability and parameters during hands-on comparison |

### D. Layer and object manipulation

| Capability | OSS requirement | Delivery / acceptance |
|---|---|---|
| Selection | Tap/select objects with clear selected state and handles | Required |
| Drag / move | Direct finger drag with stable selection | Required |
| Resize | Corner resize handles; preserve intended aspect behavior and allow precise values | Required |
| Rotate | Rotation handle and precise numeric control | Required |
| Position / size | Exact X/Y/width/height controls in contextual properties | Required |
| Layer ordering | Reorder front/back and arbitrary layer order | Required |
| Visibility | Hide/show individual elements | Required |
| Lock | Lock/unlock to prevent accidental edits | Required |
| Duplicate | Duplicate selected element | Required |
| Delete | Delete selected element with undo | Required |
| Group / ungroup | Operate on multiple selected elements as a group | Required |
| Multi-select | Select and apply supported actions to multiple objects | Required |
| Align / distribute | Align objects to canvas, guides, and each other; distribute spacing | Required |
| Grid / snap / guides | Optional grid, snapping, alignment guides and safe-area guides | Required |
| Undo / redo | History for meaningful editing operations | Required |
| Contextual panels | Selecting an object shows its relevant tools in the same editing area/tool shelf | Required |
| Touch ergonomics | One-handed, touch-first controls, clear handles and scrollable panels that do not obscure the canvas unnecessarily | Required |

### E. Export, sharing and compatibility

| Capability | OSS requirement | Delivery / acceptance |
|---|---|---|
| Image export | Render/export a composition as an image in supported formats/resolutions for sharing or asset reuse | Export capability; must not be confused with LIVE preview |
| High-resolution output | Preserve quality and offer supported output dimensions without changing the LIVE canvas ratio | Required within device/resource limits |
| Quick share | Hand exported files to Android Sharesheet/compatible apps | Required |
| Editable project save | Preserve text, layers, transforms, effects, source references and extension requirements | Required |
| Import/reopen | Reopen OSS projects without flattening | Required |
| Missing extension | Keep placeholder and full serialized data until extension is installed | Required; no destructive fallback |

## 3. OSS-specific adaptation and explicit exclusions

- Canvas ratio options from PixelLab are not copied: OSS allows only LIVE-selected Portrait 9:16 and Landscape 16:9.
- PixelLab's static photo export is an editor capability, not a second LIVE preview. Exporting a still image does not authorize a preview renderer.
- Video, GIF, Share Screen, and future streaming sources are OSS-specific element extensions and must participate in the same selection, transform, layer, and contextual-property model.
- Share Screen is added explicitly as an element. It is never the canvas itself or auto-attached.
- The Scene Editor does not start a second media renderer for preview. The compositor renders the actual LIVE scene.
- All element implementations are extensions; Core retains editing/manipulation tools and host/runtime interfaces.
- Production extension downloads require user intent and confirmation. Beta may bundle only test-required extension packages.

## 4. Completion gates

The Scene Editor cannot be called complete until all of the following are true:

1. Every feature row above is implemented or has a documented, user-approved reason for a deliberate difference.
2. The current installed PixelLab Android app has been inspected feature-by-feature; any newly discovered tools or panels are added to this matrix.
3. The user's prior PixelLab and Streamingin reference screenshots are checked against the UI and workflow.
4. Each element type is delivered and updated as an extension, and on-demand download/confirmation behavior is verified.
5. Projects survive app restart, extension removal/reinstall, and extension-version changes without losing serialized scene content.
6. Portrait/Landscape switching converts every scene in the active project safely, is undoable/recoverable, and is unavailable during LIVE.
7. Editor operations affect scene geometry only; viewport zoom/pan does not.
8. CI, emulator tests, and a milestone-based physical-device test pass. CI alone is not proof of compositor, MediaProjection, or OEM behavior.
9. No separate preview renderer has been added.

## 5. Source and review notes

- Official PixelLab Android listing: https://play.google.com/store/apps/details?id=com.imaginstudio.imagetools.pixellab
- Official listing feature inventory is a public baseline, not a complete substitute for hands-on inspection of the installed app.
- The user's prior OSS discussions establish one-hand editing, compact toolbar, selected-object contextual controls, corner resize, rotation handle, layer operations, alignment, undo/redo, autosave, and an editor workflow combining PixelLab ease with Streamingin-style direct scene editing.
- User's current explicit decision supersedes any older discussion of optional preview: preview is deferred.
- User's current explicit decision supersedes any older discussion of custom canvas ratios: only LIVE-selected Portrait/Landscape remain.
