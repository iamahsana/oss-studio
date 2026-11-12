# OSS Extension Package (.osspack)

The first package format is declarative and resource-only. It is not a plugin executable format. Every user-addable scene element/source type is represented by an extension package and manifest. Core retains the editor tools, scene graph, extension host, and trusted compositor/source adapters; extension packages declare which host capabilities they use and supply only declarative configuration and resources.

## Archive layout

A .osspack is a ZIP archive containing:
- manifest.json at the archive root.
- Resource files referenced by the manifest.

Example manifest:

    {
      "format": "oss-extension",
      "formatVersion": 1,
      "payloadType": "declarative",
      "id": "com.example.overlay",
      "name": "Example Overlay",
      "version": "1.0.0",
      "minOssVersion": "0.1.0",
      "capabilities": ["scene-element", "text-element"],
      "elementTypes": ["element.text"],
      "permissions": [],
      "dependencies": [],
      "files": [
        {
          "path": "assets/icon.png",
          "sha256": "64-character-lowercase-sha256",
          "byteSize": 1234
        }
      ]
    }

## Validation policy

- Archive size: at most 64 MiB.
- Expanded content: at most 128 MiB.
- At most 128 files; each resource at most 32 MiB.
- Manifest at most 1 MiB.
- Paths must be normalized relative paths; traversal, absolute paths, backslashes and duplicate paths are rejected.
- Every payload file must be declared in the manifest with its exact byte size and SHA-256 digest.
- Only JSON, common image formats (including GIF), fonts and audio resources are accepted as extension payload resources.
- A package may declare a host-provided capability such as scene-element, text-element, image-element, shape-element, sticker-element, emoji-element, drawing-element, gif-element, video-source, camera-source, template-pack, or share-screen-source; the manifest does not provide executable implementation code.
- Optional `elementTypes` contains at most 32 unique, stable, namespaced IDs (for example `element.text`). A package declaring element types must also declare at least one supported scene-element capability.
- Core persists the package ID, semantic version, and element type ID on both the source and every scene element that uses it. The project codec reads schema v1/v2 documents without these fields and writes schema v3 with the extension requirement when present.
- Video media selected by a user is a managed project asset, not executable extension payload. The video-source extension declares the required trusted Core capability.
- Share Screen extension declares a trusted, permission-gated Core capture capability; an extension cannot access MediaProjection or compositor internals directly.
- Templates, sticker packs, emoji packs, fonts, text presets, shapes and effects are resource/configuration extensions and are installed only on demand.
- APK, DEX, JAR, native libraries, scripts, HTML, WebAssembly and other executable formats are rejected.
- Only currently supported declarative capabilities are accepted.

This validator does not extract, install, execute or grant permissions to package contents. An Extension Center installer must validate first, extract into an isolated app-private staging directory, re-check file hashes after extraction, and only then atomically publish the extension.
