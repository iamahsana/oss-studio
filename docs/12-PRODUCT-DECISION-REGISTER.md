# OSS — Product Decision Register

Status: normative product baseline. This register records decisions that future implementation work must preserve. If a decision changes, update this file and the affected specifications in the same documentation PR before changing application behavior.

## Product identity and delivery

| ID | Decision | Status |
|---|---|---|
| P-001 | Product name is Open Stream Software (OSS). | Approved |
| P-002 | Android is the first and current implementation target. iOS is postponed; do not spend MVP effort on iOS. | Approved |
| P-003 | The normal development and testing workflow must be practical from an Android phone, using GitHub Actions for builds and artifacts. | Approved |
| P-004 | OSS is intended to be free and open source. The exact project license must be selected only after compatibility and obligation review; do not assume a license without documenting the decision. | Direction approved; license selection pending |
| P-005 | OSS is a lightweight, performance-first mobile streaming studio, not a desktop OBS clone. | Approved |
| P-006 | Use `.osspack` exclusively for installable declarative extensions and `.ossproject` for editable project interchange archives; the two formats must never share an extension. | Approved engineering convention |

## LIVE and streaming behavior

| ID | Decision | Status |
|---|---|---|
| L-001 | LIVE output stability takes priority over nonessential editor/UI work. | Approved |
| L-002 | The scene compositor output is the actual LIVE output. Preview is deferred and excluded from the initial release; do not design or implement a separate preview renderer until the user explicitly reopens the decision. | Approved |
| L-003 | LIVE session state must survive navigation between app pages. | Approved |
| L-004 | Stream destination failures must be isolated; one failed destination must not silently terminate healthy destinations. | Required product behavior |
| L-005 | Scene edits should update the compositor without unnecessarily restarting the encoder/session. | Required architecture behavior |

## Scene Editor and canvas

| ID | Decision | Status |
|---|---|---|
| S-001 | The Scene Editor must achieve PixelLab functional/workflow parity, including its complete user-facing feature set and editing flow, while using OSS's own implementation, assets, and identity. A feature-by-feature parity matrix is required; a merely PixelLab-inspired or reduced editor is not acceptable. | Approved |
| S-002 | Canvas supports only Portrait (9:16) and Landscape (16:9) in the initial release. No square, 4:5, 3:4, 4:3, or arbitrary custom dimensions. | Approved |
| S-003 | The selected LIVE output orientation is the single source of truth for canvas orientation/aspect ratio. Every scene in the active project must remain synchronized with it. | Approved |
| S-004 | The canvas is the composition surface sent to LIVE. It starts empty. | Approved |
| S-005 | Share Screen is an ordinary source/element that the user explicitly adds to a scene. It is not the canvas and must not be attached automatically. | Approved |
| S-006 | Share Screen, image, GIF, video, text, and future camera/source adapters are scene elements that can be arranged with other sources, subject to each source's validated capabilities. | Approved |
| S-007 | Editor zoom and pan affect only the editor viewport, never scene coordinates or LIVE output geometry. | Approved |
| S-008 | Orientation changes are performed while LIVE is stopped and convert all scenes in the active project predictably, without stretching content. The operation must be recoverable and undoable. | Approved behavior; conversion algorithm requires tests |
| S-009 | The canvas should dominate the editor workspace. The bottom tool shelf contains installed elements/modules; selecting an element exposes contextual controls in that same tool area. | Approved |
| S-010 | Required editor baseline includes direct drag, corner resize, rotate, crop, layer order/reorder, visibility, duplicate, lock, delete, grouping, alignment, grid/snap, guides, safe areas, contextual typography/style controls, undo/redo, autosave/recovery, and touch-first one-handed workflow. Full PixelLab parity is the acceptance target, tracked in [docs/15-PIXELLAB-FEATURE-PARITY.md](15-PIXELLAB-FEATURE-PARITY.md). | Approved scope; individual capabilities still require implementation/acceptance |

## Audio

| ID | Decision | Status |
|---|---|---|
| A-001 | Audio Mixer is a dedicated page with visible channel meters and independent source controls. | Approved |
| A-002 | Microphone, internal playback, and supported per-app capture must remain distinct channels where Android/device policy allows. Do not silently merge or substitute sources. | Approved |
| A-003 | LIVE and MONITOR are separate output buses. | Approved |
| A-004 | Per-channel controls include applicable volume/gain, mute, balance, and routing. | Approved |
| A-005 | Voice effects, noise suppression, echo cancellation, Song Request, and similar advanced processing are optional/future modules unless explicitly promoted into Core. | Modular direction approved; exact module scope pending |
| A-006 | Audio integration tests should be essential and milestone-based. Do not repeat previously passed historical tests without a regression reason. | Approved |

## Extensions, overlays, and community content

| ID | Decision | Status |
|---|---|---|
| X-001 | Essential streaming engine/Core capabilities are built into OSS. Optional capabilities are installed through the Extension Center. | Approved |
| X-002 | Community is the intended source of optional extensions/modules, subject to validation, provenance, compatibility, permissions, and license checks. | Approved |
| X-003 | Initial extensions are declarative/resource/configuration-based. Arbitrary executable community code is not allowed in Core without a separately approved sandbox design. | Approved safety boundary |
| X-004 | Overlay packs and nonessential visual features are modules, not a reason to bloat the streaming Core. | Approved |
| X-005 | Overlay/source changes that require scene reconfiguration must be prepared before LIVE starts; do not promise that enabling a module mid-stream will always take effect safely. | Approved behavior direction |
| X-006 | Extension updates must be user-controllable. Automatic update checks may be supported, but downloads default to asking the user and installation must follow Android's supported confirmation flow. | Approved direction; exact settings UI can evolve |
| X-007 | Every user-addable scene element/source type, including Text, Image, Shape, Sticker, Emoji, Drawing, GIF, Video, Template, Share Screen, and future camera/source types, is an installable extension; Core supplies the editor tools, extension host, scene graph, and compositor contracts. | Approved |
| X-008 | Production downloads are on demand only: when a user selects an uninstalled element, ask for confirmation before downloading it; never silently bulk-download the full catalog. Beta/testing builds may bundle only the extensions required for test scenarios. | Approved |

## User experience and quality

| ID | Decision | Status |
|---|---|---|
| U-001 | The app uses distinct areas/pages for Home, Studio, Scene Editor, Audio Mixer, Extension Center, and Settings. | Approved |
| U-002 | UI should be modern, compact, touch-friendly, and not a single-page legacy OBS-style layout. | Approved |
| U-003 | Essential navigation and routine editing should not be interrupted by unnecessary confirmation dialogs. Confirm destructive or risky actions. | Approved |
| Q-001 | CI build/unit/emulator checks are necessary but do not prove physical-device MediaProjection, RTMP, audio routing, thermal, or OEM behavior. | Approved quality rule |
| Q-002 | Ask the user to install/test APKs only at meaningful integration milestones, not for every small code change. | Approved workflow |
| Q-003 | Diagnostics are metadata and signal-level summaries by default, never audio/video recordings; export only after explicit user action. | Approved |
| Q-004 | Logs must identify subsystem and individual source/channel/destination, redact credentials, and avoid blocking LIVE-critical threads. | Approved |

## Change control

1. A product decision is not changed by an implementation PR, code convenience, or an unreviewed assumption.
2. Any proposed change must identify the affected decision IDs, explain the reason and trade-offs, and update this register plus affected normative documents.
3. Product-scope changes require explicit user approval before implementation.
4. When code and documentation disagree, treat the documentation baseline as the intended behavior; record the implementation as a deviation to be corrected, not as an automatic change in product direction.
5. Keep decisions marked "pending" or "requires validation" distinct from approved requirements. Never describe a prototype or CI pass as a verified production capability.
