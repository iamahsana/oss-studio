# OSS Extension Package (.osspack)

The first package format is declarative and resource-only. It is not a plugin executable format.

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
      "capabilities": ["scene-overlay"],
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
- Only JSON, common image formats, fonts and audio resources are accepted.
- APK, DEX, JAR, native libraries, scripts, HTML, WebAssembly and other executable formats are rejected.
- Only currently supported declarative capabilities are accepted.

This validator does not extract, install, execute or grant permissions to package contents. An Extension Center installer must validate first, extract into an isolated app-private staging directory, re-check file hashes after extraction, and only then atomically publish the extension.
