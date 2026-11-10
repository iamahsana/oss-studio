# OSS Scene Editor — PixelLab Parity Matrix

**Status:** Required product specification; implementation is not yet complete.  
**Reference application:** PixelLab — Text on pictures, Android package `com.imaginstudio.imagetools.pixellab` (App Holdings).  
**Policy:** Match the reference editor's interaction model, workspace hierarchy, editing workflow, and functional breadth. Do not copy proprietary source code, artwork, fonts, stickers, templates, or other assets without permission.

## 1. Non-negotiable acceptance rule

OSS Scene Editor is not considered PixelLab-equivalent merely because it can place text and images on a canvas. Every applicable capability below must have a working implementation, persisted project data, LIVE compositor support where relevant, and automated coverage. A visible but non-functional control does not count.

The reference UI should be audited against current PixelLab screenshots and workflows. User-provided screenshots are the primary visual reference for matching the editor layout. Use original OSS icons/artwork and legally distributable assets.

## 2. UI/UX parity

- [ ] Dedicated editor workspace; do not embed the editor as one small card inside a general scrolling page.
- [ ] Canvas is the primary work surface and uses available screen space; fit-to-screen and zoom/pan affect the viewport only, never stored scene geometry.
- [ ] PixelLab-like hierarchy: top-level project/actions, prominent canvas, contextual object toolbar/property panels, and bottom insertion/tool shelf.
- [ ] Selecting an object exposes relevant controls without obscuring the canvas unnecessarily.
- [ ] Contextual panels are scrollable and do not cover the design in a way that prevents editing.
- [ ] Consistent selection bounds and touch handles for moving, resizing, rotating, cropping, and perspective editing where supported.
- [ ] Object/layer list with clear selection, ordering, visibility, lock, duplicate, rename, merge/group, and delete actions.
- [ ] Menus, dialogs, color pickers, font pickers, sliders, and confirmation flows follow the reference interaction pattern.
- [ ] Portrait/landscape canvas dimensions remain governed by the project's LIVE output orientation; editor viewport orientation is independent.
- [ ] Usable on small Android screens and landscape mode; touch targets must be practical.

## 3. Project and layer model

- [ ] Multiple independently editable objects on one canvas.
- [ ] Add, select, rename, duplicate, delete, hide/show, lock/unlock, reorder, align, group/ungroup, and merge supported layers.
- [ ] Move, resize, rotate, crop, flip, opacity, and precise numeric geometry controls.
- [ ] Undo/redo with meaningful edit transactions and stable history across compound gestures.
- [ ] Save, reopen, duplicate, and recover projects without losing object settings or asset references.
- [ ] Persist all style/effect parameters in project data; do not rely on transient UI state.
- [ ] Unknown or uninstalled extension-owned objects remain visible as recoverable placeholders and must not corrupt a project.
- [ ] Project migration is versioned, transactional where multi-object changes occur, and preserves legacy projects.

## 4. Text editing

- [ ] Multiple text objects and text editing by direct selection/double-tap.
- [ ] Font family, custom font import, font size, weight, italic, alignment, case conversion, and text color.
- [ ] Solid fill, linear gradient, radial gradient, and image/texture fill.
- [ ] Letter spacing, word spacing, line spacing, and per-word/per-range font or color formatting.
- [ ] Curved/circular text and text-on-path controls.
- [ ] 3D text with depth, direction, and shading controls.
- [ ] Text background/fill panel, outline/stroke, shadow, inner shadow, reflection, emboss, and mask effects.
- [ ] Effect-specific parameters (e.g. blur, opacity, offsets, width, color) rather than one generic on/off switch.
- [ ] Convert text to an editable vector/shape representation where supported by the target workflow.

## 5. Images, stickers, shapes, and drawing

### Images
- [ ] Import multiple images from device storage and use an image as an object or texture.
- [ ] Crop, scale, rotate, flip, opacity, outline/stroke, and perspective/warp transform.
- [ ] Background removal with transparent output and an editable/recoverable result.
- [ ] Image filters/effects including hue, saturation, vignette, stripes, and comparable image adjustments.
- [ ] Preserve source asset provenance and avoid destructive edits to the original imported file.

### Stickers and shapes
- [ ] Insert and customize stickers, emojis, and vector/basic shapes.
- [ ] Shape fill, outline, stroke width, opacity, shadow, transform, and layer ordering.
- [ ] Community/downloadable sticker and shape packs must be optional extensions.

### Drawing
- [ ] Freehand drawing with brush size, color, opacity, and practical brush palette.
- [ ] Drawing output becomes a selectable/editable layer that can be moved, resized, rotated, and styled.
- [ ] Drawing layer supports shadow and other applicable object effects.

## 6. Canvas background and templates

- [ ] Background can be a solid color, gradient, or image.
- [ ] Linear, diagonal, vertical, horizontal, and radial gradient controls where applicable.
- [ ] Meme presets, poster/layout templates, and quote insertion/library workflows.
- [ ] Templates and quote/sticker collections are downloadable extensions, not mandatory core payloads.

## 7. Export and output

- [ ] Export at user-selected dimensions/resolution, including high-resolution output (4K target).
- [ ] Preserve alpha/transparency when the selected format supports it.
- [ ] Export formats and quality settings are explicit and validated.
- [ ] Share/export workflows are separate from LIVE output; a scene must also render correctly through the LIVE compositor.
- [ ] Preview/export rendering and LIVE rendering share the same scene geometry and effect semantics; no hidden geometry conversion.

## 8. Extension architecture constraints

All user-addable scene elements and optional creative capabilities must be extension-backed and installed only when requested. The editor shell, project model, persistence, extension security, and LIVE compositor remain built-in foundations.

Initial extension families to support:
- `element.text`
- `element.image`
- `element.shape`
- `element.sticker`
- `element.drawing`
- `element.background`
- `effect.text.*`
- `effect.image.*`
- `effect.object.*`
- `font.family`
- `template.poster.*`
- `template.meme.*`
- `collection.quote.*)

Extension packages remain declarative and non-executable under the current security model. Capability IDs, object type IDs, schemas, and version constraints must be stable and namespaced. Do not expand the package validator by allowing arbitrary scripts, HTML, JavaScript, WASM, DEX, JAR, native libraries, or APK payloads.

For every integrated upstream component, record its source repository, exact version/commit, license, notices, modifications, and limitations. A component that only supplies a sub-feature must never be described as a complete editor replacement.

## 9. Delivery gates

A capability is marked complete only when all of the following are true:
1. The actual editing action works, including edge cases and touch interaction.
2. Its settings persist through save, close, and reopen.
3. Undo/redo behaves correctly.
4. The project codec round-trips its data and safely handles unknown extension data.
5. LIVE compositor output matches the editor's intended geometry and visual result.
6. Automated unit/instrumentation coverage passes on supported Android API levels.
7. The capability has been checked against the PixelLab reference workflow.

**Beta is not a waiver of this parity target.** Beta may expose a clearly labeled incomplete subset for testing, but it must not be presented as full PixelLab parity. No preview work is included in this scope until the user explicitly reopens that decision. Existing audio functionality is out of scope and must not be destabilized.

## 10. Reference sources

- [PixelLab on Google Play — official Android listing](https://play.google.com/store/apps/details?id=com.imaginstudio.imagetools.pixellab)
- [PixelLab App Store listing and public version notes](https://apps.apple.com/id/app/pixellab-text-photo-editor/id6757263162)

The Android listing documents text and 3D text, text effects and fills, custom fonts, stickers/emojis/shapes, image import, drawing, solid/gradient/image backgrounds, project save/reopen, background removal, perspective editing, image effects, meme presets, quotes, and high-resolution export. Public release notes may add further capabilities; keep this matrix version-aware and update it during reference audits.
