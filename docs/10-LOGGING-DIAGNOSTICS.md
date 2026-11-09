# OSS — Logging and Diagnostics Specification

Status: required cross-cutting foundation. Every subsystem must have its own identifiable logs from its first implementation.

## Goals

- Make failures traceable to the responsible subsystem and individual object.
- Correlate events across a LIVE session without combining unrelated sources.
- Keep logging low-overhead and safe during long streaming sessions.
- Let a user export a useful diagnostic package for a bug report.
- Never collect stream content or credentials as a side effect of diagnostics.

## Logging architecture

Use one shared logging service with separate subsystem tags/categories and structured fields. Do not let each feature invent an incompatible logger or write arbitrary files independently.

Every record should support:
- timestamp (UTC and elapsed/realtime offset where useful)
- severity: VERBOSE, DEBUG, INFO, WARN, ERROR
- subsystem/category and component
- event name and stable error code when applicable
- human-readable message
- app version, build number, Android API, device model (coarse device metadata)
- correlation identifiers when available: sessionId, projectId, sceneId, sourceId, channelId, destinationId, extensionId, operationId
- exception class and sanitized stack trace for failures

Use stable subsystem tags, for example:
- `OSS/App`, `OSS/Navigation`, `OSS/Account`, `OSS/Permissions`
- `OSS/Project`, `OSS/Scene`, `OSS/SceneSource`, `OSS/Asset`
- `OSS/Media/Image`, `OSS/Media/GIF`, `OSS/Media/Video`, `OSS/Media/Screen`
- `OSS/Audio/Core`, `OSS/Audio/Mic`, `OSS/Audio/Internal`, `OSS/Audio/Channel`, `OSS/Audio/Router`
- `OSS/Stream/Core`, `OSS/Stream/Encoder`, `OSS/Stream/RTMP`, `OSS/Stream/Destination`
- `OSS/Network`, `OSS/Extension`, `OSS/Storage`, `OSS/Performance`, `OSS/Diagnostics`

Individual objects must be distinguishable. For example, each audio channel and each RTMP destination logs with its own stable ID, not just the generic audio or stream tag.

## Severity and event rules

- VERBOSE/DEBUG: development details and state transitions; disabled or reduced in production by default.
- INFO: important lifecycle events such as source added/removed, stream started/stopped, destination connected, project saved.
- WARN: recoverable problems, fallback paths, permission limitations, reconnect attempts, dropped frames crossing a threshold.
- ERROR: failed operations, source/encoder/decoder failures, destination failures, corrupted project/module, unrecoverable state.
- FATAL: process-threatening failures or invariant violations; preserve a compact crash breadcrumb trail where possible.

Every asynchronous operation must log start/result/failure with the same operationId. Every stateful subsystem must log meaningful state transitions. Errors must include a stable code, actionable context, and exception details where available. Avoid logging every video frame or PCM buffer; use counters, periodic summaries, and threshold-triggered events.

## Per-object isolation

- Scene and media sources: sourceId, source type, sceneId, lifecycle, decoder/renderer state, load duration, frame/drop counters, and failure reason.
- Audio: one channelId per input/channel; log device route, capture availability/restrictions, format, meter summaries, mute/gain/routing changes, underruns/overruns, and start/stop errors. Never log audio samples.
- Streaming: one destinationId per RTMP/RTMPS endpoint; log sanitized host identity, connection state, handshake/connection duration, reconnect count, queue/backpressure, bitrate, dropped frames, and server/client error code. Never log full URLs if they contain keys or tokens.
- Extensions: extensionId/version, validation result, permissions, compatibility, install/update/remove failures.
- Project/storage: projectId and operationId, save/restore/import/export result, duration, file size, validation errors. Do not log private asset contents or absolute paths unless explicitly included in a user-approved diagnostic export and scrubbed.

One destination or optional source failing must not erase or obscure the logs of other components.

## Storage and performance

- Write logs asynchronously on a bounded background queue; logging must never block the UI, audio callback, GL thread, encoder callback, or network send loop.
- Use a bounded rolling log store with rotation and a configurable size/retention policy. Keep recent detailed events and longer-lived compact error summaries.
- Apply backpressure: if the queue is full, drop low-priority records first and increment a visible diagnostic counter; never block LIVE-critical threads.
- Avoid per-frame/per-sample file writes. Emit periodic aggregated counters and threshold-based warnings.
- Keep Logcat tags useful during development. Production file logs should be structured (JSON Lines or an equivalent versioned schema) so reports can be parsed.
- Logging failures must not crash the app or stop LIVE.

## Privacy and security

Never log:
- RTMP/RTMPS stream keys, full credential-bearing URLs, passwords, access/refresh tokens, cookies, authorization headers, private keys, or billing secrets
- raw audio/video, screenshots, microphone samples, chat contents, viewer personal data, or private media content
- sensitive account identifiers

Sanitize URLs before logging; retain only protocol, destination label, and a redacted host/endpoint fingerprint where useful. Apply a central redaction filter before any sink receives a record. Do not rely on each feature author to remember redaction.

Logs remain local by default. No automatic upload. User explicitly chooses to export/share diagnostics. Export should show a preview/summary and allow the user to inspect what is included before sharing.

## Diagnostic export package

A user-approved export should include:
- app/build version and relevant device/Android metadata
- selected time range and log schema version
- subsystem logs with correlation IDs
- crash breadcrumbs and sanitized stack traces
- stream destination status without keys or credential-bearing URLs
- performance summaries (memory/thermal/battery where available, FPS, dropped frames, encoder/network counters)
- source/channel lifecycle and error codes
- a manifest listing included files and redaction status

Do not include audio/video recordings or project/media files by default. If a support case needs a project file, request it separately with explicit user consent. Export can be a ZIP archive; provide a preview and a clear confirmation step before creating the shareable package.

## Developer and user experience

- Settings > Diagnostics: current log status, storage used, clear logs, export diagnostics, and a short privacy explanation.
- Studio: concise LIVE/session health indicators; detailed logs remain in Diagnostics rather than overwhelming the streaming UI.
- A failed source/channel/destination should expose a “View details” action that opens the matching filtered log view.
- Debug builds may expose verbose controls. Production builds should keep safe operational logging enabled and verbose traces disabled unless temporarily enabled by the user.
- A bug report should identify the app/build version and attach the diagnostic package only after explicit user action.

## Acceptance tests

1. Trigger one controlled failure in each major subsystem and verify it appears under the correct tag/category with a stable error code.
2. Run two sources/channels/destinations concurrently; cause one to fail and verify IDs and logs remain isolated.
3. Verify a sessionId ties related scene, audio, encoder, and destination events together.
4. Verify redaction with test stream keys, tokens, passwords, and credential-bearing URLs; assert none appear in Logcat, files, or exported ZIP.
5. Stress logging during a sustained LIVE session; verify bounded storage, no blocking of critical threads, and no measurable frame/audio regression beyond the agreed budget.
6. Simulate a full log queue and storage failure; verify graceful dropping/rotation and that LIVE continues.
7. Export diagnostics and inspect the archive manifest, time range, included files, and privacy preview.

## Implementation rule

This specification is mandatory for all future modules and adapters. A feature is not considered integration-ready until it emits structured lifecycle/error logs, uses the central redaction layer, and includes at least one diagnostic test.