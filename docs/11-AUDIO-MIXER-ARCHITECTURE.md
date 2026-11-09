# OSS Audio Mixer Architecture

Status: architecture decision record (ADR), implementation in progress.

## Product contract

OSS Core owns a digital mixer with independent source channels and two independent output buses:
- LIVE: PCM mixed for the streaming encoder.
- MONITOR: PCM mixed for the selected local playback device.

A channel can route to LIVE, MONITOR, both, or neither. Song Request defaults to LIVE only. No source is implicitly routed to either bus until the scene/session policy enables it.

## Required channel model

Initial core channels:
- Microphone
- Android playback capture (combined system playback where permitted)
- Media / video overlay audio
- Song Request
- USB / external audio input when Android exposes it

Future extension channels must use the same source contract; extensions do not own the master mixer.

Each channel exposes gain, mute, solo, pan/balance, peak/RMS metering, and independent LIVE/MONITOR routing. Master LIVE and MONITOR buses have independent gain and meters.

## Proven upstream components

Pinned encoder dependency: RootEncoder 2.8.1.

Use:
- `MicrophoneSource` for mic PCM capture.
- `InternalAudioSource` for Android 10+ playback capture, subject to MediaProjection and playback-capture policy.
- `BufferAudioSource` as the single encoder-facing PCM source. Its `setBuffer` API accepts PCM16 data and timestamps, and it emits silence when no data is supplied.
- RootEncoder's AAC encoder and RTMP transport for encoding and delivery.

Do not use `MixAudioSource` as the main mixer. It combines mic and internal playback before OSS can apply independent channel routing, monitoring, or Song Request policy. It may remain an optional constrained fallback only if explicitly selected and clearly labelled.

## Signal path

```
MicrophoneSource ───────┐
InternalAudioSource ────┤
Media / Song Request ───┼─> timestamp alignment + channel DSP
Future source adapters ─┘       │
                                ├─> LIVE bus ─> BufferAudioSource ─> AAC/RTMP
                                └─> MONITOR bus ─> selected Android output
```

The mixer must not block capture, encoder, GL, or UI threads. Capture callbacks publish bounded PCM frames to a dedicated real-time-safe mixing path. No network, file I/O, logging serialization, locks with unbounded waits, or UI callbacks are permitted on the audio processing callback.

## PCM contract

- PCM signed 16-bit little-endian initially.
- One negotiated sample rate and channel layout per session (default 48 kHz stereo; fallback only after encoder/device capability validation).
- Timestamp-based alignment; late frames are counted and dropped, gaps become silence.
- Saturating accumulation to avoid integer overflow and clipping.
- Gain and pan are applied per channel before bus summation.
- Limiter / headroom policy on each bus before output.
- Fixed-capacity queues and reusable buffers; bounded memory and no per-frame allocation in the hot path.
- Metering uses peak and RMS windows; UI receives sampled snapshots off the audio thread.

## Android platform limits

Android playback capture is not universal. The user must grant MediaProjection and the source application must allow playback capture; protected, policy-restricted, or excluded usages may be silent. Per-application selection is only exposed when Android APIs and source metadata genuinely allow it. Never claim arbitrary app isolation on devices where the platform does not provide it.

MONITOR output is subject to Android audio routing, focus, latency, Bluetooth delay, and OEM behavior. The app must report the actual selected output and measured/estimated latency rather than promise sample-accurate hardware loopback.

## Lifecycle and ownership

- One session owns its capture sources, projection callback registrations, mixer, output bus, and encoder-facing BufferAudioSource.
- Capture source callbacks are stopped before MediaProjection is stopped.
- Encoder is stopped before final audio source release.
- Teardown is idempotent and every stage emits structured diagnostics.
- No audio source may retain a stale MediaProjection across LIVE restarts.
- Permission denial or playback capture failure must be visible and must not silently substitute a different source.

## Verification gates

1. Unit tests: PCM alignment, gain, pan, mute, solo, routing, saturating sum, silence gaps, late-frame drops, and queue overflow.
2. Android tests: microphone-only, internal-only, both independently, and denied playback capture.
3. Routing tests: Song Request LIVE-only; MONITOR-only source; mute/solo; separate master levels.
4. Device tests: speaker, wired headset, USB audio, Bluetooth where supported; report latency and OEM restrictions.
5. Stress: sustained LIVE, CPU pressure, queue saturation, source removal, projection stop, and rapid start/stop.
6. No actual user LIVE test until all core routing tests pass and the Studio UI makes source and output state explicit.

## Logging

Use distinct component identifiers for each source, channel DSP, LIVE bus, MONITOR bus, encoder bridge, and device router. Record state, format, queue depth, dropped frames, peak/RMS, routing changes, and error codes. Never record raw PCM or include stream credentials.
