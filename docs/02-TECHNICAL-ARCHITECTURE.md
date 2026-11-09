# OSS — Technical Architecture

## High-level pipeline

```text
Scene Source Registry
        |
Source Manager -> Decode / Capture Adapters
        |
Media Pipeline (decode, transform, crop, scale)
        |
Scene Compositor
        |
LIVE Encoder -> Destination Manager -> RTMP/RTMPS outputs

Audio Sources -> PCM Pipeline -> Mixer / Processors
                              |-> LIVE bus
                              |-> MONITOR bus

UI -> Use Cases -> Core APIs
Extension Events <-> Core Event Bus
```

The UI never owns media processing. Streaming and media lifecycles are managed by services/controllers below the presentation layer. LIVE session state survives navigation.

## Architectural boundaries
Use a small number of Gradle modules initially. Split modules only when boundaries are stable and independent compilation/testing provides real value. Avoid circular dependencies.

Proposed logical areas:
- app and navigation
- core common/model
- persistence and project management
- scene/source/media
- rendering/compositor
- audio
- streaming/destinations
- extension contracts and event bus
- performance/diagnostics
- feature UI: home, studio, editor, mixer, extensions, settings, account

These are logical boundaries, not a requirement to create every Gradle module immediately.

## Dependency direction
- Feature UI depends on stable Core interfaces and use cases.
- Core model does not depend on Android UI.
- Source adapters implement Core contracts.
- Renderer consumes scene snapshots and source frames; it does not query UI state.
- Streaming consumes compositor output and destination configuration.
- Extensions interact through declared contracts/events, never direct access to internal engine objects.

## Persistence
- Room is the candidate for projects, scenes, elements, destinations metadata, and extension registry.
- DataStore is the candidate for app preferences.
- Credentials and stream keys must use Android-protected storage; never store them in plain project exports or logs.
- Database schema changes require explicit migrations.
- Autosave and crash recovery are required.

## Rendering and media
- Android MediaCodec is the primary hardware codec interface where compatible.
- Compositor implementation is selected after a prototype and device benchmark.
- Decoding and asset loading are off the UI thread.
- GIF decoding is progressive with bounded frame/cache memory.
- Hidden or disabled sources should stop unnecessary work.
- No preview pipeline in the initial release.
- Scene changes should update compositor state without restarting the encoder.

## Audio
Use a normalized PCM pipeline with independently managed sources, per-source controls, processors, meters, and LIVE/MONITOR buses. Android playback capture is conditional and must expose only sources that are actually capturable. A source failure is isolated and reported; do not silently substitute another source.

## Streaming
Hide the chosen RTMP library behind an adapter. The selection must follow maintenance, license, API, stability, and performance review. Destination state machines are independent. Share encoding only when destination constraints are compatible.

## Extension system
Start with declarative manifests, resources, templates, and configuration-driven modules. Validate signature/provenance, compatibility, permissions, and assets. Do not execute arbitrary community code in Core. Extension failures must be isolated from LIVE-critical processing.

## Observability
Diagnostics collect operational metadata and signal levels only by default. No audio/video recording. Never log stream keys, passwords, tokens, or private credentials.
