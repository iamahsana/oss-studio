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
- Current mixer core uses saturating PCM16 output to prevent integer wraparound. A proper look-ahead or low-latency limiter/headroom stage remains a required follow-up before public LIVE testing; hard clipping alone is not considered final mastering.
- Fixed-capacity SPSC (single-producer/single-consumer) PCM frame queues per capture source, with preallocated slots, timestamp metadata, and drop/oversize counters. Queue-full policy drops the newest frame rather than blocking a capture callback. Reusable mixer/output buffers; bounded memory and no per-frame allocation in the hot path.
- Metering uses peak and RMS windows; UI receives sampled snapshots off the audio thread.

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
