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
- Persist the selected LIVE output orientation as a global/LIVE setting. It is the authoritative source for scene canvas orientation and output aspect ratio; scene records must not independently select arbitrary ratios or dimensions.
- On orientation change while LIVE is stopped, migrate all scenes in the active project atomically using the documented geometry conversion. Preserve a recoverable prior snapshot and make the operation undoable. Reject or defer orientation changes during an active LIVE session.
- Credentials and stream keys must use Android-protected storage; never store them in plain project exports or logs.
- Database schema changes require explicit migrations.
- Autosave and crash recovery are required.

## Rendering and media
- The compositor receives a scene snapshot whose canvas orientation is synchronized with the active LIVE output setting. The encoder must derive its aspect ratio from that same setting (or its synchronized canonical scene canvas), never from editor viewport measurements.
- Canonical logical canvas reference sizes are 1080×1920 Portrait and 1920×1080 Landscape. Encoder resolution may scale to supported stream limits while preserving the selected aspect ratio. Arbitrary aspect ratios and custom canvas dimensions are not supported in the initial release.
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


## RootEncoder compositor integration contract (2.8.1)

- The streaming adapter obtains the shared compositor through `StreamBase.getGlInterface()` after video preparation. OSS must not instantiate a second GL renderer for scene layers.
- RootEncoder's `GlStreamInterface.addFilter` and `removeFilter` enqueue filter operations for its GL render loop; they are asynchronous commands, not synchronous mutations. Scene state and source lifecycle must therefore tolerate a short apply/remove boundary.
- `SurfaceFilterRender` creates its external-OES texture, `SurfaceTexture`, and `Surface` during GL filter initialization. Its surface-ready callback is posted to the Android main thread. MediaProjection must target that exact surface only after it becomes available.
- The Share Screen controller must be stopped on the main thread before its projection session is revoked. The stream owner must also ensure GL teardown releases any remaining filters. Never release the GL renderer while assuming a queued filter operation has already completed.
- `AndroidLiveRuntimeFactory` owns the per-LIVE RootEncoder stream and OSS audio mixer. It prepares the encoder, obtains the stream's GL interface, attaches the approved projection through `ShareScreenLayerController`, and reports LIVE readiness only after RTMP, audio, and Share Screen prerequisites are ready.
- `ShareScreenSessionService` is the sole owner of the Android MediaProjection session. `ShareScreenLayerController` owns only the layer callback, VirtualDisplay, and GL filter. Removing a layer must not independently revoke the service-owned projection.
- Teardown is dependency ordered: detach Share Screen; stop the audio mixer/capture/monitor asynchronously; stop RTMP; revoke MediaProjection only after capture owners are stopped; then release RootEncoder. If Share Screen detach fails, retain RTMP/GL and projection owners for retry. If the mixer worker remains active, retain the entire downstream graph. If capture cleanup remains incomplete, retain MediaProjection. Failed RTMP release remains retryable and must not be cached as RELEASED.
- RootEncoder GL `addFilter` and `removeFilter` enqueue operations for its GL render loop; they are asynchronous commands, not synchronous proof that a filter has been applied or removed. The layer controller retains resource handles when a release call throws, but device-level verification is still required for GL queue timing and actual surface release.
- CI currently verifies compilation, unit tests, and app startup on emulator API 29, 32, 35, and 36. These checks do not prove real MediaProjection capture, RTMP delivery, audio routing, or OEM-specific lifecycle behavior; those remain hardware validation gates.
