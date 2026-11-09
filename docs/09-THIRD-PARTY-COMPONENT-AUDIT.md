# Third-Party Component Audit

Status: initial candidate review; no runtime dependencies are approved by this document alone.

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
