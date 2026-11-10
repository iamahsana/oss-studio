# OSS — Product Specification

## Product
Open Stream Software (OSS) is a lightweight, modular Android live-streaming studio. It is designed for creators who need scene composition, media sources, audio control, and multiple streaming destinations from a phone.

## Product principles
1. **LIVE-first:** stability of the live output takes priority over nonessential work.
2. **Performance-first:** memory, CPU/GPU, thermal load, battery, and network use are design constraints from day one.
3. **Modular:** essential capabilities belong in Core; optional capabilities are installed through the Extension Center.
4. **Safe by default:** community extensions are validated, permission-scoped, and unable to control or destabilize Core.
5. **Phone-first:** development, installation, and testing must be practical from an Android phone.

## Initial platform and scope
- Android first; iOS is postponed.
- Target Android 10+ is the current product target; final minSdk is confirmed during compatibility validation.
- Kotlin and Jetpack Compose are the current implementation direction.
- No desktop dependency for normal development/testing workflow.
- Scene preview is explicitly out of the initial release scope. The editor canvas defines the composition sent to LIVE; no duplicate preview renderer is required.
- LIVE output orientation is configured in LIVE settings and governs every scene canvas. Only Portrait (9:16) and Landscape (16:9) are supported initially; no square, 4:5, 3:4, 4:3, or arbitrary custom dimensions.

## Main application areas
- **Home:** recent projects, activity, shortcuts, and module information. It is not the LIVE control page.
- **Studio:** streaming destinations, quality, connection state, quick audio controls, LIVE status, and Go Live / End Live.
- **Scene Editor:** canvas whose orientation follows LIVE output settings, elements, layers, asset management, transforms, undo/redo, and scene settings.
- **Audio Mixer:** independent source channels, meters, mute, gain, balance, and LIVE/MONITOR routing.
- **Extension Center:** discover, install, update, inspect, and remove extensions.
- **Settings:** account, appearance, global preferences, storage, backups, module permissions, updates, and diagnostics.

## Scene and source model
A scene is an ordered composition of independently managed sources. The canvas starts empty. Text, image, GIF, video, Share Screen, and later camera/USB adapters are sources that users explicitly add. Share Screen is not the canvas and is never attached automatically.

Portrait and Landscape are the only supported LIVE output orientations in the initial release. The active LIVE output setting is the single source of truth for orientation and canvas aspect ratio; the Scene Editor must follow it and must not expose independent per-scene aspect-ratio, arbitrary preset, or custom width/height controls. Use logical reference dimensions of 1080×1920 for Portrait and 1920×1080 for Landscape; encoder output may scale these reference dimensions to a supported stream resolution without changing their aspect ratio. Changing LIVE orientation is allowed only while LIVE is stopped. It must update every scene in the active project through a deterministic, non-distorting layout conversion, preserve relative placement/size as far as possible, clamp only when necessary, and be undoable/recoverable. Editor viewport rotation, zoom, and pan must never mutate scene geometry. Share Screen remains an ordinary source element that users add explicitly to a scene.

The editor should closely follow PixelLab's interaction model and workflow without copying its proprietary code, assets, or branding. Required capabilities include drag, resize, rotate, crop, layer ordering, duplicate, lock, delete, grouping, grid/snap, safe areas, undo/redo, and contextual tools.

## Streaming
- Multiple RTMP/RTMPS destinations are required.
- Each destination has independent configuration, state, reconnect behavior, and error reporting.
- Failure of one destination must not terminate other healthy destinations.
- Reuse encoded output only when codec, dimensions, frame rate, and other output constraints are compatible.
- Perform a resource/network preflight before starting multi-destination LIVE.
- Scene changes must not restart the streaming session or encoder unnecessarily.
- LIVE must continue while the user navigates between app pages.

## Audio
The mixer is a dedicated page. Sources may include microphone, capturable per-app playback, combined internal audio fallback, media-source audio, Song Request, USB audio, and future extension sources. Availability depends on Android version, device, permissions, and source-app capture policy.

Each available source has independent controls and routing. LIVE and MONITOR are separate buses. No silent source substitution is allowed. Audio processing must be isolated from video processing so an audio failure cannot stall the video pipeline.

## Accounts and monetization
Guest access supports essential testing and basic use. Authentication is persistent and must not be required again for every debug build. Premium entitlement and payment provider remain implementation decisions. Debug-only developer access must never ship in production. Essential streaming, scene editing, common media sources, Share Screen, and basic mixing are intended to remain available without a subscription.

## Shareable projects
Scene/project packages use the proposed `.osspack` format: a ZIP container with manifest, scene/project data, media assets, thumbnails, version metadata, and license/provenance information. Import must validate archive paths, size, file types, integrity, version compatibility, and conflicts before extracting.

## Non-goals for initial release
- iOS application.
- Live scene preview or duplicate rendering pipeline.
- Arbitrary executable community code without a proven sandbox.
- Guaranteed capture of every Android app's internal audio.
- Unbenchmarked promises about maximum simultaneous GIFs/videos or destinations.
