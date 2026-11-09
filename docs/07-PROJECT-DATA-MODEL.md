# OSS — Project Data Model

## Core entities
- **Project:** stable ID, name, created/updated timestamps, active scene ID, canvas defaults, project version.
- **Scene:** stable ID, project ID, name, logical width/height, background, ordered element references.
- **SceneElement:** stable ID, source ID, transform, crop, opacity, visibility, lock state, z-order, grouping metadata.
- **Source:** stable ID, source type, configuration, asset reference, enabled state, lifecycle state.
- **Asset:** stable ID, local URI/path, MIME type, size, checksum, dimensions/duration where applicable, provenance/license metadata.
- **DestinationProfile:** stable ID, platform label, endpoint reference, secret reference, stream settings, enabled state.
- **ExtensionRecord:** extension ID, version, source/provenance, license, declared capabilities, permissions, compatibility, enabled state.

## Geometry
Persist scene geometry in logical canvas coordinates, not screen pixels. Transform operations must remain stable when the editor rotates or resizes. Portrait and Landscape are explicit canvas modes. Any orientation conversion is an explicit operation that updates geometry predictably and can be undone.

## Persistence strategy
Room is the current database candidate for structured project data and registry state. DataStore is the candidate for global preferences. Store media assets as managed files with references from the database. Use transactions for structural edits and migrations for schema changes.

## Autosave and recovery
- Persist meaningful edits automatically.
- Debounce high-frequency drag updates while preserving crash recovery.
- Keep a recoverable last-known-good project state.
- Detect interrupted writes and offer recovery rather than silently discarding work.
- Provide export/backup independently from the live project database.

## .osspack package
A shareable project package is a ZIP container with:
- versioned manifest
- project and scene data
- referenced media assets
- thumbnails/previews (static thumbnails only; not a runtime preview feature)
- extension requirements
- license and asset provenance notices

Import must validate manifest schema/version, archive paths, entry count, compressed/expanded size, MIME types, checksums, extension requirements, and name/ID conflicts before committing imported data. Never include stream credentials or account tokens in exports.
