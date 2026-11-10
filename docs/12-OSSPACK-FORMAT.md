# OSS Extension Package (.osspack)

## Status and scope

The current `.osspack` format is a **declarative resource package**, not an executable plugin format. The application does not load code from an extension. The trusted OSS Core owns the editor, scene graph, compositor, source adapters, permission gates, and rendering behavior.

Packages may provide supported resources and configuration. They cannot add arbitrary runtime behavior. A package being accepted by the archive validator does not mean that every feature family listed in the long-term PixelLab-parity plan is already implemented by the editor.

## Archive layout

A `.osspack` file is a ZIP archive containing:
- `manifest.json` at the archive root.
- Resource files referenced by the manifest.

Minimal manifest example for the current validator:

    {
      "format": "oss-extension",
      "formatVersion": 1,
      "payloadType": "declarative",
      "id": "com.example.overlay",
      "name": "Example Overlay",
      "version": "1.0.0",
      "minOssVersion": "0.1.0",
      "capabilities": ["scene-overlay"],
      "files": [
        {
          "path": "assets/icon.png",
          "sha256": "64-character-lowercase-sha256",
          "byteSize": 1234
        }
      ]
    }

## Manifest contract implemented today

The validator currently recognizes these capability strings only:
- `scene-overlay`
- `media-source`
- `text-widget`

Any other capability is rejected. In particular, capability names such as `scene-element`, `text-element`, `shape-element`, and `drawing-element` are **not currently accepted**. The previous draft example that used those names was ahead of the validator and must not be used to build packages.

The current parser reads:
- `format`, `formatVersion`, and `payloadType`
- `id`, `name`, `version`, and `minOssVersion`
- `capabilities`
- `files` entries with `path`, `sha256`, and `byteSize`

The parser also accepts an optional `elements` array (maximum 64 entries). Each entry contains:
- `typeId`: unique, namespaced under the package ID, for example `com.example.overlay.badge`.
- `name`: display name, at most 80 characters.
- `kind`: one of `text`, `image`, `sticker`, `shape`, `background`, `font`, `gif`, or `video`.
- `resourcePath`: optional path to a file declared in `files`. It is required for image, sticker, background, GIF, video, and font kinds.
- `defaults`: optional map of primitive string, number, or boolean values. Nested objects and arrays are rejected.

A package declaring scene elements must include the `scene-overlay` capability. The Extension Center re-reads installed manifests and verifies each declared resource's size and SHA-256 before listing element definitions. This makes definitions discoverable, but does **not** mean every kind already has an insertion workflow or renderer in Scene Editor.
Fields such as `elementTypes`, `permissions`, and `dependencies` are not currently interpreted or enforced. Do not rely on them for package compatibility or security decisions.

## Validation and security policy

- Archive size: at most 64 MiB.
- Expanded content: at most 128 MiB.
- At most 128 files; each resource at most 32 MiB.
- Manifest at most 1 MiB.
- Paths must be normalized relative paths; traversal, absolute paths, backslashes, control characters, and duplicate paths are rejected.
- Every payload file must be declared in the manifest with its exact byte size and SHA-256 digest.
- Allowed resource extensions: JSON, PNG, JPG/JPEG, WebP, GIF, TTF/OTF, MP3, WAV, and OGG.
- APK, DEX, JAR, native libraries, scripts, HTML, WebAssembly, and other executable formats are rejected.
- The validator does not extract, install, execute, or grant permissions to package contents.

An installer must validate the archive first, extract into an isolated app-private staging directory, re-check file hashes after extraction, and only then atomically publish the extension. It must not trust archive paths or manifest metadata without validation.

## Planned contract evolution

The long-term extension system is intended to cover optional scene elements and resource families such as text presets, fonts, shapes, stickers, emoji, drawing assets, GIF/video source adapters, templates, and effects. These are **planned capability families**, not all supported by the current validator or editor.

Before adding new capability identifiers, the implementation must:
1. Define stable, namespaced extension IDs and element `typeId` values.
2. Define the exact declarative schema and Core-owned renderer/source adapter for each capability.
3. Add validator allowlist entries and positive/negative tests together.
4. Preserve unknown extension-owned configuration when a package is missing or disabled.
5. Keep permission-sensitive operations (for example screen capture) behind trusted Core APIs.
6. Version the manifest contract and project serialization with explicit backward-compatible migrations.

Executable extension code remains out of scope for this package format.