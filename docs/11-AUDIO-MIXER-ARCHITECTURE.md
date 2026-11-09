# OSS Audio Mixer Architecture

Status: architecture decision record (ADR), implementation in progress.

## Product contract

OSS Core owns a digital mixer with independent source channels and two independent output buses:
- LIVE: PCM mixed for the streaming encoder.
- MONITOR: PCM mixed for the selected local playback device.

A channel can route to LIVE, MONITOR, both, or neither. Song Request defaults to LIVE only. The mixer owns all source activation and routing; the user never chooses a pre-LIVE source mode. No Mic-only / Internal-only / Mic+Internal wizard or startup picker exists. On LIVE start, OSS initializes the available core channels automatically using the saved mixer state. The microphone channel starts when permission and an input are available; Android playback capture starts when MediaProjection and the target app's playback-capture policy allow it. If a source is unavailable, its mixer strip shows a clear unavailable/permission status while other available channels continue. The mixer must not silently substitute one source for another.

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
- `InternalAudioSource` for Android 10+ playback capture, subject to MediaProjection and playback-capture policy. RootEncoder 2.8.1 stores its projection in the process-wide `MediaProjectionHandler`; the OSS adapter must enforce one active projection-backed audio session, stop/unregister the source before clearing that reference, and never allow overlapping sessions to overwrite it.
- `BufferAudioSource` as the single encoder-facing PCM source. RootEncoder 2.8.1 accepts `ByteArray` PCM through `setBuffer`, including a timestamp-in-microseconds overload; it emits silence where no data is supplied. OSS mixer output therefore uses interleaved PCM16 little-endian `ByteArray` buffers to avoid an unnecessary format conversion.
- RootEncoder's AAC encoder and RTMP transport for encoding and delivery. `RtmpStreamSession` requires an explicit `AudioSource`; it has no default `NoAudioSource` or video-only fallback.

Do not use `MixAudioSource` as the main mixer. It combines mic and internal playback before OSS can apply independent channel routing, monitoring, or Song Request policy. It is not part of the default product path and must not introduce a source-mode picker.

## Capture session adapter

`RootEncoderAudioCaptureSession` now owns separate `MicrophoneSource` and `InternalAudioSource` instances, each feeding its own `RootEncoderPcmCaptureSink` and bounded queue. It sets RootEncoder's negotiated format directly instead of calling `AudioSource.init()` for these capture-only adapters: in RootEncoder 2.8.1, the source `start()` methods create their own `AudioRecord`, while `InternalAudioSource.init()` also creates an unrelated microphone recorder. Avoiding that duplicate initialization prevents unnecessary recorder allocation. On session start it attempts every available core input automatically: microphone requires `RECORD_AUDIO`; internal playback also requires `RECORD_AUDIO`, Android 10+, an active MediaProjection, and target-app playback-capture permission. A failure or missing permission is reported on that channel and does not silently replace or disable the other source. The adapter serializes RootEncoder's process-wide `MediaProjectionHandler` ownership, unregisters/stops `InternalAudioSource` before clearing only the projection instance it owns, and defers projection-stop cleanup away from RootEncoder's callback thread. The capture adapter is implemented but is not yet connected to the LIVE runtime or mixer worker.

## Signal path

```
MicrophoneSource ───────┐
InternalAudioSource ────┤
Media / Song Request ───┼─> timestamp alignment + channel DSP
Future source adapters ─┘       │
                                ├─> LIVE bus ─> BufferAudioSource ─> AAC/RTMP
                                └─> MONITOR bus ─> selected Android output
```

The implemented PCM path now consists of `RootEncoderPcmCaptureSink` (copies RootEncoder `Frame` PCM and its microsecond timestamp into one source queue), `SpscPcmFrameQueue` (bounded timestamped SPSC frames), `PcmTimestampAligner` (preallocated per-source blocks, silence gaps, late-frame accounting), `PcmMixerCore` (independent LIVE/MONITOR routing, gain, pan, mute, solo and Peak/RMS), `PcmMixerEngine` (reusable fixed-size block orchestration), and `RootEncoderBufferAudioOutput` (timestamped LIVE bus submission to `BufferAudioSource`). `processBlock` is intended for one dedicated mixer worker; capture callbacks only enqueue frames. RootEncoder's `BufferAudioSource.setBuffer` is synchronized internally, so only that worker may submit output. No network, file I/O, diagnostic serialization, or UI callbacks are permitted in the mixer worker.

## PCM contract

- PCM signed 16-bit little-endian initially.
- One negotiated sample rate and channel layout per session (default 48 kHz stereo; fallback only after encoder/device capability validation). The RTMP session default audio encoder rate is aligned to 48 kHz; capture adapters must resample any source that cannot provide that format before queueing.
- Timestamp-based alignment; late frames are counted and dropped, gaps become silence.
- Saturating accumulation to avoid integer overflow and clipping.
- Gain and pan are applied per channel before bus summation.
- Current mixer core applies independent block-level peak limiting to LIVE and MONITOR before writing saturating PCM16 output. This avoids integer wraparound and prevents the two buses from sharing limiter gain. Device-level mastering and loudness validation remain separate verification tasks.
- Fixed-capacity SPSC (single-producer/single-consumer) PCM frame queues per capture source, with preallocated slots, timestamp metadata, and drop/oversize counters. Queue-full policy drops the newest frame rather than blocking a capture callback. Reusable mixer/output buffers; bounded memory and no per-frame allocation in the hot path.
- Metering uses peak and RMS windows; UI receives sampled snapshots off the audio thread.


## User-facing mixer controls

The Mixer page exposes the two currently implemented core capture strips: Microphone and Internal/Game audio. Each strip has 0–200% gain, mute, solo, stereo pan/balance, independent LIVE and MONITOR routing, and a sampled peak meter. LIVE and MONITOR master gains are independent. These settings are persisted in app-private SharedPreferences; no captured audio or stream credentials are stored there.

Changes are applied to the active `OssAudioMixerSession` through `AudioMixerControlBus` without restarting the capture graph. A new LIVE session loads the saved configuration before capture starts. Source availability remains runtime-derived: microphone permission controls mic availability, while internal capture additionally requires an active MediaProjection and source-app playback-capture policy permission. The UI does not offer a pre-LIVE mic/internal mode picker.


## Multi-destination RTMP transport

When more than one enabled RTMP profile exists, `AndroidLiveRuntimeFactory` selects `MultiRtmpStreamSession` backed by RootEncoder `MultiStream`. The shared encoder feeds independent RTMP clients; destination callbacks are tracked separately. A connected destination keeps the overall session LIVE even if another destination fails. The shared encoder is released only after all destination clients have been disconnected.

RootEncoder documents `MultiStream` as experimental. The adapter therefore treats connection state and cleanup as explicit per-destination operations, but production readiness still requires device/network testing with multiple real ingest endpoints. One enabled profile continues to use the established single-destination `RtmpStreamSession`.

## Android platform limits

Android playback capture is not universal. The user must grant MediaProjection and the source application must allow playback capture; protected, policy-restricted, or excluded usages may be silent. Per-application selection is only exposed when Android APIs and source metadata genuinely allow it. Never claim arbitrary app isolation on devices where the platform does not provide it.

MONITOR output is subject to Android audio routing, focus, latency, Bluetooth delay, and OEM behavior. The app must report the actual selected output and measured/estimated latency rather than promise sample-accurate hardware loopback.

## Lifecycle and ownership

- One session owns its capture sources, projection callback registrations, mixer, output bus, and encoder-facing BufferAudioSource.
- Capture source callbacks are stopped before MediaProjection is stopped. `InternalAudioSource` is stopped and its callback unregistered before clearing RootEncoder's static projection reference.
- Encoder is stopped before final audio source release.
- Teardown is idempotent and every stage emits structured diagnostics.
- No audio source may retain a stale MediaProjection across LIVE restarts.
- Permission denial or playback capture failure must be visible and must not silently substitute a different source.

## Verification gates

1. Unit tests: PCM16 little-endian decoding/encoding, gain, pan, mute, solo, independent LIVE/MONITOR routing and master gain, saturating sum, peak/RMS meters, bounded SPSC FIFO ordering, timestamps, oversized frames, queue overflow, timestamp alignment, adjacent frames, gaps, late frames, frames spanning output blocks, and full engine block processing.
2. Android tests: automatic channel startup with both sources available; microphone-only availability; internal-only availability; both independently; and denied playback capture. These are test conditions, not user-facing pre-LIVE modes.
3. Routing tests: Song Request LIVE-only; MONITOR-only source; mute/solo; separate master levels.
4. Device tests: speaker, wired headset, USB audio, Bluetooth where supported; report latency and OEM restrictions.
5. Stress: sustained LIVE, CPU pressure, queue saturation, source removal, projection stop, and rapid start/stop.
6. No actual user LIVE test until all core routing tests pass and the Studio UI makes source and output state explicit. LIVE startup must not ask the user to choose a microphone/internal combination.

## Logging

Use distinct component identifiers for each source, channel DSP, LIVE bus, MONITOR bus, encoder bridge, and device router. Record state, format, queue depth, dropped frames, peak/RMS, routing changes, and error codes. Never record raw PCM or include stream credentials.
