# OSS — Security and License Policy

## Open-source reuse
Open-source availability does not mean unrestricted reuse. Before adopting a dependency or source component, record:
- project URL and exact version/commit
- license and required notices/attribution
- modification obligations and distribution conditions
- maintenance activity and known security issues
- Android compatibility, binary size, runtime cost, and API fit

Do not merge code with unclear or incompatible licensing. Maintain a third-party notices inventory and a change/provenance log.

## Extension safety
Initial community extensions are declarative/resource-based. Arbitrary executable community code is out of scope until a robust sandbox and permission model are designed and tested.

Every extension manifest must declare identity, version, compatibility, capabilities, permissions, assets, and provenance. Validate package paths, size limits, MIME/file types, hashes, and conflicts. Reject path traversal and malformed archives.

Extensions cannot:
- own or replace the streaming engine
- access stream credentials directly
- block audio/video processing
- mutate internal engine state outside documented APIs
- prevent Core from stopping or removing the extension

## Credentials and privacy
- Keep stream keys, tokens, and passwords out of logs, crash reports, scene packages, and diagnostics.
- Use Android-protected storage for secrets.
- Diagnostics are metadata/levels only by default; no audio/video recording.
- Request only permissions needed for a feature and explain why.
- Network integrations must disclose their destination and data use.

## Project package import
The proposed `.osspack` ZIP must be validated before extraction. Enforce limits for compressed and expanded size, entry count, nesting, paths, file types, and manifest version. Reject traversal, symlinks where unsafe, duplicate/conflicting paths, and unsupported content.

## Release separation
Debug-only developer bypasses and test credentials must not be present in production builds. Release builds require dependency/license review, secret scanning, and a documented signing-key custody process.
