# OSS Source Reuse Assessment — Scene Editor

Assessment date: 2026-11-10

## Mandatory selection rule

OSS must prefer an existing, maintained, license-compatible implementation that has a verifiable test/build history. Reuse should be limited to the smallest practical integration surface. Every adopted source must record its upstream URL, exact tag/commit, license, local modifications, and OSS validation results.

A repository description or feature checklist is not proof that a component works in OSS. Candidates must be inspected, built, and exercised against the required OSS workflow before adoption.

## Required Scene Editor contract

The OSS editor is a persistent multi-layer scene compositor, not a single-photo annotation screen. It must support independent scene elements and LIVE sources, including Share Screen, media, text, and future extension-provided elements. Each element must retain editable properties and serialize into the OSS project model. LIVE rendering must consume the scene graph without flattening the scene into one static bitmap.

The user-visible editor must ultimately match the agreed PixelLab workflow and feature scope. A candidate that only edits a photo and exports one flattened image cannot replace the scene editor.

## Candidate assessment

| Candidate | License / upstream state | Useful reusable capabilities | Decision |
|---|---|---|---|
| [burhanrashid52/PhotoEditor](https://github.com/burhanrashid52/PhotoEditor) | MIT; Kotlin; 4.5k+ stars; release v.3.1.1; upstream commit inspected: `4a6967d3067d37010f9673034db27f53cf70e449` | Drawing, text, custom fonts, emoji, stickers, filters, gesture scale/rotate, undo/redo, save | **Do not use as the Scene Editor base.** Its documented contract edits a background photo and saves a flattened image. Consider only as a future isolated photo-annotation extension after build and device validation. |
| [jonghough/ArtisteX](https://github.com/jonghough/ArtisteX) | BSD-3-Clause; Java; last upstream commit 2017-07-09 | Editable vector/image/text layers, shapes, transforms, bitmap/SVG export | **Reject for beta.** Explicitly a proof of concept; README says redo and multi-touch zoom are not implemented. Too stale for a critical editor foundation. |
| [robeshell/compose-image-editor](https://github.com/robeshell/compose-image-editor) | MIT; Kotlin/Compose; small repository; last push 2026-06-17 | Compose wrapper for crop, drawing, mosaic, text, stickers | **Reject as Scene Editor base.** It also exports a flattened image; limited text styling and small upstream validation footprint. |
| [Allay054/Photo-Editor](https://github.com/Allay054/Photo-Editor) | No license declared; small repository; last push 2026-09-18 | Claims text, crop, shapes, layers, history, transforms | **Reject.** No declared license means OSS cannot safely incorporate it; repository is too small to treat its feature checklist as proof of production readiness. |

## Current decision

No inspected candidate meets the full OSS scene-editor contract. Do not replace the existing OSS scene graph with a photo-editor SDK merely to claim source reuse.

Retain the existing OSS project model, layer graph, and compositor as the integration boundary. Reuse mature, compatible upstream components for isolated capabilities where their output remains editable or can be represented as an OSS asset/source. Do not adopt a flattened-image pipeline for LIVE scene composition.

## Validation status

This is a source review, not an upstream integration test. None of the candidates above has been vendored or added as an OSS dependency. Before any future adoption:

1. Pin an exact release tag or commit.
2. Verify the license text at that exact revision.
3. Build the upstream sample/library with the OSS Android toolchain.
4. Test the exact required gesture/edit/export path on emulator and a physical Android phone.
5. Record OSS-specific modifications and regression results.
