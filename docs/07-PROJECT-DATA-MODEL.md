# OSS — Project Data Model

## Core entities
- **Project:** stable ID, name, created/updated timestamps, active scene ID, project version. Canvas orientation is not an independent per-project preference when LIVE output settings are global.
- **Scene:** stable ID, project ID, name, canonical logical width/height derived from the selected LIVE orientation, background, ordered element references.
- **SceneElement:** stable ID, source ID, transform, crop, opacity, visibility, lock state, z-order, grouping metadata.
- **Source:** stable ID, source type, configuration, asset reference, enabled state, lifecycle state.
- **Asset:** stable ID, local URI/path, MIME type, size, checksum, dimensions/duration where applicable, provenance/license metadata.
- **DestinationProfile:** stable ID, platform label, endpoint reference, secret reference, stream settings, enabled state.
- **ExtensionRecord:** extension ID, version, source/provenance, license, declared capabilities, permissions, compatibility, enabled state.

## Geometry
Persist scene geometry in logical canvas coordinates, not screen pixels. Transform operations must remain stable when the editor rotates, zooms, pans, or resizes. The selected LIVE output orientation is authoritative for all scene canvases: Portrait = 1080×1920 logical reference size; Landscape = 1920×1080. Do not persist arbitrary aspect ratios or user-defined canvas dimensions in the initial release. An orientation change is an explicit project-wide operation performed only while LIVE is stopped; convert element positions and sizes proportionally without distortion, preserve layer order/crop/rotation/group relationships, clamp only when required by bounds, and provide undo plus last-known-good recovery. All scenes must be synchronized atomically.

## Persistence strategy
Current implementation: SQLiteOpenHelper stores versioned project documents and indexed project summaries in `oss_projects.db`; writes are transactional and version 2 migrates the recovery table for databases created by version 1. The repository serializes database work on a single I/O executor. DataStore remains a candidate for global preferences. Store media assets as managed files with references from the database. Use explicit transactions and forward-only migrations for structural schema changes.

## Autosave and recovery
- Persist meaningful edits automatically.
- Debounce high-frequency drag updates while preserving crash recovery.
- Keep a recoverable last-known-good project state. Each successful save atomically snapshots the previous document before replacing it; the first save seeds recovery with the initial document.
- Debounced autosave exposes an immediate flush operation and flushes the latest pending snapshot when the autosave owner closes.
- Detect interrupted writes and offer recovery rather than silently discarding work.
- Provide export/backup independently from the live project database.

## Media asset ingestion and rendering status
- User-selected assets enter through Android's Storage Access Framework document picker and are copied into app-private storage; external provider URIs are not retained as long-term project paths.
- Import enforces per-type byte limits, an allowlist of raster-image/video MIME types, matching file-signature checks, and SHA-256 integrity metadata. SVG, HTML, and unknown formats are rejected.
- Image and GIF elements can be rendered as scene layers. Video import and poster-frame display are present, but continuous video playback in the LIVE compositor is not yet accepted as complete; it requires a Media3/RootEncoder integration spike and device-level frame-pacing/lifecycle tests. Do not advertise imported video as a playing LIVE source until those gates pass.

## .osspack package
A shareable project package is a ZIP container with:
- versioned manifest
- project and scene data
- referenced media assets
- thumbnails/previews (static thumbnails only; not a runtime preview feature)
- extension requirements
- license and asset provenance notices

Import must validate manifest schema/version, archive paths, entry count, compressed/expanded size, MIME types, checksums, extension requirements, and name/ID conflicts before committing imported data. Never include stream credentials or account tokens in exports.
