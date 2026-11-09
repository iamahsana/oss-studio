# OSS — Performance and Quality Requirements

Performance is an architectural requirement, not a post-release optimization task.

## Runtime rules
- No media decoding or heavy processing on the UI thread.
- No duplicate preview render pipeline in the initial release.
- Use hardware codec paths when supported and compatible.
- Bound image/GIF/video caches and release inactive resources.
- Decode GIF frames progressively; do not preload every frame into RAM.
- Stop unnecessary work for hidden, disabled, or inactive sources.
- Keep audio processing independent from video rendering.
- Isolate optional source failures from the LIVE-critical pipeline.
- Avoid heavy UI animation and unnecessary recomposition.
- Prioritize LIVE work over nonessential background tasks.

## Performance Manager
Collect CPU/GPU load where available, memory pressure, thermal state, battery impact, frame timing/drops, encoder health, network throughput, and destination state. It may recommend lower FPS/bitrate or disabling costly sources. It must not silently change user output quality without clear indication and user control.

## Benchmark matrix
Measure on representative physical Android devices:
- cold launch and time to enter Studio
- time from Go Live tap to confirmed output
- steady-state and peak RAM
- CPU/GPU utilization and thermal trend
- battery drain over sustained sessions
- frame rate and dropped frames
- number and resolution of concurrent video/GIF/image sources
- number of compatible simultaneous destinations
- recovery after source failure, network interruption, and app navigation
- one-hour and longer soak sessions

Do not publish a maximum number of simultaneous media sources until measured on defined devices and settings.

## Diagnostics
Diagnostics are opt-in/exportable metadata and signal-level summaries. Do not record audio/video by default. Redact credentials and private identifiers. Include app version, device/API metadata, engine states, error codes, and relevant timing/counter data.

## Logging quality gate

All subsystems must use the shared structured logging service with per-component/object identifiers, stable error codes, correlation IDs, bounded asynchronous storage, and central secret redaction. Logging must not block LIVE-critical threads. Diagnostics remain local and export only after explicit user action. See [Logging and Diagnostics](10-LOGGING-DIAGNOSTICS.md).

## Quality gates
A stage is accepted only after CI build/tests, launch test, key workflow test, regression review, and resource measurements. Test plans should be grouped into meaningful milestones rather than asking the user to repeat already-passed checks.
