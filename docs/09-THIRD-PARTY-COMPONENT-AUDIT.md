# Third-Party Component Audit

Status: candidate review in progress. RootEncoder 2.8.1 dependency resolution is CI-verified, but runtime engine adoption is not yet approved.

## Adoption rule

OSS must prefer mature, maintained, independently tested open-source components over reimplementing established subsystems. Before adding a dependency, record its exact repository, immutable version/tag, license file and obligations, Android/API compatibility, maintenance status, known issues, transitive dependencies, security posture, and device test results.

Do not add a library merely because it has a useful feature list. Verify that it can support OSS's scene graph, independent sources, LIVE priority, lifecycle, and multi-destination design without forcing a duplicate renderer or unstable fork.

## Initial candidates

| Subsystem | Candidate | Initial assessment | Decision |
|---|---|---|---|
| Stream encoding and transport | [RootEncoder](https://github.com/pedroSG94/RootEncoder) | Strong candidate. The previous OSS prototype used RootEncoder 2.8.1 and successfully streamed to an RTMP destination. Upstream documents RTMP/RTMPS, RTSP, SRT, UDP, hardware codecs, image/GIF/text inputs, file sources, screen capture, and OpenGL filters. | Re-audit the exact current release, license, API, multi-output constraints, and source/compositor integration. Do not blindly copy the old integration. |
| Video/audio file playback | [AndroidX Media3 ExoPlayer](https://github.com/androidx/media) | Mature Android media stack with buffering, decoders, media sources, and playback controls. Suitable candidate for local/remote media playback; not itself a scene compositor or stream encoder. | Candidate; pin a stable version after checking compatibility with RootEncoder's input pipeline. |
| Image and GIF loading | [Coil](https://github.com/coil-kt/coil) | Kotlin-first image loader with caching/downsampling and Android GIF decoder extension. Could serve asset browsing and still/animated image decoding. | Candidate; test memory limits, GIF frame pacing, cancellation, and handoff to the LIVE compositor. |
| Alternative image/GIF loading | [Glide](https://github.com/bumptech/glide) | Established Android image/media loader; supports image, video still, and animated GIF loading with caching/resource pooling. | Keep as an alternative; avoid shipping both Coil and Glide without a concrete reason. |
| Low-latency audio I/O | [Oboe](https://github.com/google/oboe) | Google-maintained native audio library selecting AAudio/OpenSL ES based on device API, with latency tuning and test tooling. It is an I/O foundation, not a ready-made broadcast mixer or per-app capture solution. | Candidate for a dedicated native audio path only if RootEncoder's audio pipeline cannot meet requirements. Benchmark before adoption. |
| Screen capture | Android MediaProjection APIs, potentially via RootEncoder's supported screen source | Platform API is required for user-authorized capture. OSS's scene canvas remains independent; a screen-capture source is added as one layer/source, not the whole canvas. | Prefer supported upstream implementation where possible; verify foreground-service and Android 14+ consent/lifecycle requirements. |
| Scene composition | RootEncoder OpenGL/filter/source pipeline plus a compatible scene/layer abstraction | Feature overlap exists, but feature lists do not prove support for arbitrary independent transformable layers, masks/crops, z-order, and PixelLab-like editing. | **Not approved yet.** Evaluate source code and examples; identify whether an existing compositor can be adapted. Do not build a custom renderer until this audit shows a real gap. |

## Important boundaries

- RootEncoder is a streaming/encoding candidate, not automatically a complete multi-layer scene editor.
- Media3 is a media playback candidate, not the LIVE output encoder.
- Coil/Glide are asset-loading and decoding candidates, not full scene renderers.
- Oboe is low-latency audio I/O, not a complete channel-strip UI, audio router, or Android per-app capture API.
- Android playback capture is governed by platform restrictions and the target app's capture policy. Never promise per-app capture when Android/device policy does not permit it.
- Multi-destination RTMP must be validated independently. Do not assume a library's multiple protocol support means multiple concurrent independent RTMP outputs are supported efficiently.

## Next audit and acceptance sequence

1. Inspect exact upstream source, release notes, license files, and API compatibility for RootEncoder and its current 2.8.x line.
2. Compare RootEncoder's built-in sources and OpenGL path with the required scene graph: independent source transforms, crop, rotation, alpha, z-order, text/image/GIF/video, screen capture, and audio routing.
3. Verify whether one encoded video stream can be safely fanned out to multiple compatible destinations, and how per-destination reconnect/error isolation would work. If incompatible settings require separate encoders, estimate device load and define preflight limits.
4. Evaluate Media3 and one image/GIF loader for decoder compatibility, lifecycle handling, memory use, and frame pacing.
5. Benchmark audio routing on actual Android devices before selecting Oboe or another audio backend.
6. Add dependencies only after these checks. Pin exact versions and retain license/provenance notices in the repository.
7. Build a small integration spike using upstream components before designing custom glue. The spike must be disposable and must not become a parallel engine.

## References

- RootEncoder: https://github.com/pedroSG94/RootEncoder
- RootEncoder 2.8.1 API docs: https://pedrosg94.github.io/RootEncoder/
- AndroidX Media3: https://developer.android.com/media/media3/exoplayer
- Coil GIF support: https://coil-kt.github.io/coil/gifs/
- Coil project and license: https://coil-kt.github.io/coil/
- Glide: https://github.com/bumptech/glide
- Oboe: https://github.com/google/oboe
- Android high-performance audio guidance: https://developer.android.com/ndk/guides/audio


## RootEncoder 2.8.1 source audit (2026-11-09)

**Verified upstream facts**

- The exact 2.8.1 release tag is published and its repository metadata identifies Apache License 2.0; the tagged source contains `LICENSE.txt`. OSS must still ship the required license text and notices for the exact components actually distributed.
- The upstream README documents RTMP including RTMPS, screen streaming, file sources, real-time image/GIF/text, and OpenGL filters. The 2.8.1 release notes additionally describe buffer pooling, PCM `BufferAudioSource`, codec options, and source/lifecycle fixes.
- `StreamBase` owns one video encoder, one audio encoder, one `GlStreamInterface`, one current video source, and one current audio source. Its public `startStream(endPoint)` starts one endpoint through its implementation. The audited API does **not** expose a built-in multi-RTMP destination manager.
- `GlStreamInterface` exposes ordered filter operations (add, insert, replace, remove, clear). `MainRender` chains those filters in order, feeding each filter the previous filter's texture. Object filter classes include image, GIF, text, and surface-backed objects. `BaseObjectFilterRender` exposes a sprite with scale, translation, rotation, and alpha. This is a credible base for composited overlay layers.
- RootEncoder has a `ScreenSource` that consumes a MediaProjection and a `MixAudioSource` that combines microphone and internal playback into one audio source. Those are useful existing implementations, but the latter is a single combined source, not the independently controllable multi-channel LIVE/MONITOR mixer OSS requires.
- The GL pipeline's preview is optional and distinct from stream output. OSS can avoid attaching/starting a preview surface for the initial product while using the output render path.

**Architecture implications / unresolved gaps**

1. **Scene editor:** RootEncoder is more capable than a simple filter list: its ordered object filters and sprite transforms can support image/GIF/text overlays and potentially surface-backed video overlays. However, the audited public abstraction is still a source plus an ordered GL filter chain, not a ready-made scene graph with stable object IDs, editable per-layer state, grouping, snap/grid, safe areas, undo/redo, or a PixelLab-like editor. OSS should persist a scene graph and translate it to upstream filter operations; do not fork or rewrite the GL renderer unless a measured gap requires it.
2. **Independent video layers:** `SurfaceFilterRender` appears to provide the upstream surface-backed object path for a player/decoder surface. Validate multiple simultaneous surface objects, lifecycle, alpha/transform, frame pacing, and decoder load in an integration spike before promising unlimited video layers.
3. **Cropping and masks:** A general crop filter exists, but it may crop the full pipeline rather than an individual object. Per-layer source crop, clipping, masks, and aspect-fit/fill semantics remain unproven. Keep these as explicit acceptance tests.
4. **Share Screen:** `ScreenSource` is a video-source implementation and occupies the standard `StreamBase` video-source slot, so it cannot by itself satisfy OSS's layer model. However, source inspection found a promising same-compositor path: `SurfaceFilterRender` creates an external-OES `SurfaceTexture` and exposes a `Surface`; Android `MediaProjection.createVirtualDisplay(...)` can target that surface. This should allow screen pixels to enter the existing ordered GL object-filter chain as one layer alongside image/GIF/text/video layers, without a second renderer. This is a source-level integration path, not yet a verified runtime result. Prototype MediaProjection → SurfaceFilterRender → shared GL output, including Android 10–16 permission/foreground-service lifecycle, rotation/resize, pause/stop/revoke, and source removal while LIVE.
5. **Audio:** `MixAudioSource` combines mic and internal audio into one output. It does not by itself provide independent channel strips, per-source faders/mutes, separate LIVE and MONITOR buses, or app-by-app playback capture. OSS must not present those capabilities as already solved.
6. **Multi-RTMP:** A single stock `RtmpStream` owns one `RtmpClient`, but the 2.8.1 source exposes a promising upstream extension point. `StreamBase` provides protected encoded video/audio callbacks (`getVideoDataImp`, `getAudioDataImp`) and codec/configuration callbacks. A custom `StreamBase` subclass can own multiple independent `RtmpClient` instances, call `setVideoInfo`/`setAudioInfo` for each, and fan each encoded buffer out through `sendVideo`/`sendAudio`. Verified in `BaseSender.sendMediaFrame`: each client copies the supplied buffer into its own pooled buffer and bounded sender queue using `trySend`, so destination queues and dropped-frame counters are independent. This is materially better than one full `StreamBase` per destination and avoids re-encoding. Remaining risks: fan-out copy cost grows with destination count; callbacks execute sequentially; each destination needs its own `ConnectChecker`, reconnect state, timeout, and failure isolation; one blocked or expensive client call must not stall other destinations. The standard `StreamBase.getStreamClient()` abstraction exposes only one client, so OSS needs its own destination manager/adapter rather than assuming stock `RtmpStream` already supports multiple endpoints. Prototype and benchmark 1/2/3 destinations, mixed network quality, disconnect/reconnect, queue saturation, and one destination failing while others remain live. Do not claim multi-destination support until those tests pass.
7. **No-preview policy:** Do not attach a preview surface in the initial OSS path. Verify the GL output pipeline starts from stream setup alone, and confirm that no preview rendering or duplicate output encoding is created.

**Provisional decision**

Keep RootEncoder 2.8.1 as the leading encoding/GL-composition candidate. Its dependency resolution passed GitHub Actions run 42553614177, its Apache-2.0 license text is retained under `third_party/RootEncoder/`, and its source features are verified. This only verifies artifact retrieval and build dependency resolution; it does not verify runtime behavior or grant final architecture approval. The scene-object chain is promising, but multi-RTMP fan-out, Share Screen as a coexisting layer, independent video surfaces, per-object crop/masks, and independent audio buses remain blockers for final architecture approval.

## CI baseline correction

The earlier AndroidX metadata failure was corrected by enabling `android.useAndroidX=true` in root `gradle.properties`. Baseline CI run 42553420488 completed successfully with unit tests, debug APK assembly, and artifact upload. RootEncoder dependency-resolution probe run 42553614177 also completed successfully. These are build checks only, not runtime streaming tests.


### Multi-RTMP fan-out source trace (2.8.1)

- `StreamBase` declares protected abstract `getVideoDataImp` and `getAudioDataImp`; `RtmpStream` implements these by forwarding to its owned `RtmpClient`.
- `RtmpClient.sendVideo` and `sendAudio` call `RtmpSender.sendMediaFrame` with the encoded buffer and `MediaCodec.BufferInfo` metadata.
- `BaseSender.sendMediaFrame` acquires a buffer from that sender's own pool, clones the encoded bytes, and attempts to enqueue the resulting `MediaFrame` with `queue.trySend`. On saturation it releases the buffer and increments that sender's dropped-frame counter.
- This means one custom `StreamBase` encoder callback can fan out to several independently pooled RTMP senders. It does **not** prove that multi-destination is production-safe: sequential copy overhead, queue capacity, callback timing, connect lifecycle, and reconnect isolation still need a device benchmark.


### Share Screen as a composited object — source trace (2.8.1)

- `SurfaceFilterRender.initGlFilter` creates an external texture, a `SurfaceTexture`, and a `Surface`; it updates the texture during `drawFilter` and releases the texture/surface during filter teardown.
- `ScreenSource` instead creates a `VirtualDisplay` targeting the stream's main input `SurfaceTexture`, which is why it occupies the normal `VideoSource` slot.
- Android's `MediaProjection` API accepts a caller-provided `Surface` for `createVirtualDisplay`. Therefore a separate OSS source adapter can plausibly direct MediaProjection into the `Surface` owned by a `SurfaceFilterRender` already attached to `GlStreamInterface`.
- This avoids a second compositor in the design. It still needs a prototype to verify callback thread/lifecycle, Surface validity, frame pacing, resizing/rotation, and current Android MediaProjection foreground-service and consent requirements.


## Emulator compatibility automation (2026-11-09)

The Android CI workflow now includes a separate emulator smoke-test matrix for API 29, 32, 35, and 36. Instrumentation tests launch the application, verify the Home screen, navigate to Editor, and verify the Editor page. This is a UI/lifecycle smoke test only; it does not simulate hardware encoder quality, MediaProjection behavior on OEM devices, audio routing, thermal throttling, or real network performance. CI results must be reviewed before treating any API level as passed.


### OSS multi-destination prototype status (2026-11-09)

The first implementation boundary is now in the app source:

- `RtmpDestinationPort` defines the destination contract.
- `RootEncoderRtmpDestination` wraps one upstream `RtmpClient`, retains a stable destination ID, exposes connection state, and writes sanitized destination-specific diagnostics.
- `RtmpDestinationManager` registers independent destinations, fans encoded audio/video buffers to connected destinations, catches dispatch/configuration failures per destination, and tracks dispatch-failure counters.
- Instrumentation tests cover duplicate-ID rejection, connected-only dispatch, destination removal, and one destination throwing during video dispatch while another still receives the frame.

The fan-out manager uses the upstream sender's per-client bounded queue. This prevents socket I/O from being performed by the manager's dispatch call, but each client performs a buffer copy and queue attempt synchronously. Therefore fan-out cost still grows with destination count. The current prototype does not yet connect this manager to a custom `StreamBase` subclass or the real encoder callbacks. It is a tested-in-isolation architecture component, not a working multi-RTMP LIVE feature.

### CI run management

The Android workflow now cancels superseded runs on the same Git ref. This avoids a backlog of redundant matrix jobs while several commits are pushed in quick succession. The newest run remains the source of truth for the final code state.


### Share Screen layer controller prototype (2026-11-09)

An initial `ShareScreenLayerController` now routes an already-authorized Android `MediaProjection` into RootEncoder's upstream `SurfaceFilterRender`, then inserts that filter into the existing `GlStreamInterface` chain. The controller supports scene-space scale/position updates, releases the virtual display before removing the filter, handles system projection stop, and emits source-correlated diagnostics.

This is deliberately only the projection-to-scene-layer adapter. It does not request capture permission or start the required mediaProjection foreground service; those responsibilities must be implemented in a dedicated Android service/permission flow. It has not yet been runtime-validated. CI compilation and lifecycle tests must pass before APK testing, followed by physical-device checks on Android 10 and Android 14+ / 16.


### Share Screen foreground session service (2026-11-09)

A dedicated `ShareScreenSessionService` now owns the Android foreground-service lifetime for a user-approved MediaProjection session:

- Declares `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PROJECTION`, with a non-exported service marked `foregroundServiceType="mediaProjection"`.
- Enters foreground mode with an ongoing low-importance notification before calling `MediaProjectionManager.getMediaProjection`, matching the required lifecycle ordering.
- Exposes the active projection through a local binder so the scene layer controller can consume the same authorized session.
- Stops cleanly on explicit stop or Android's projection `onStop` callback; logs startup failures and lifecycle transitions without logging permission payloads.
- Does not request permission itself. The Activity/Compose permission launcher must call `ShareScreenSessionService.start(...)` only after the system consent result succeeds, then bind to the service and pass its active projection to `ShareScreenLayerController`.

This service and the existing scene-layer controller are not yet wired into the UI and have not been validated on a physical device. Android 10–16 build and lifecycle checks remain required; Android 14+ behavior must be specifically exercised because MediaProjection foreground-service enforcement is stricter.
