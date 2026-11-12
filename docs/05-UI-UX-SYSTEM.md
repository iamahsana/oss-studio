# OSS — UI/UX System

## Navigation
Primary destinations: Home, Studio, Scene Editor, Audio Mixer, Extension Center, Settings. Keep navigation predictable and shallow. LIVE session state is global and remains active when changing pages.

## Home
Home is a true dashboard: recent projects, recent activity, shortcuts, extension updates, and project recovery notices. Do not turn Home into the LIVE control surface.

## Studio
Studio is the central LIVE operations page. It contains destination setup/status, stream quality, scene selection, quick audio controls, connection health, LIVE statistics, and Go Live / End Live. Detailed channel configuration belongs in Audio Mixer; global app preferences belong in Settings.

Each destination displays its own state and error. The primary action changes from Go Live to End Live when the session is active. Ending one destination must not imply ending all destinations unless the user chooses that action.

## Scene Editor
The Scene Editor must achieve PixelLab functional and workflow parity, not merely borrow its general style. Reproduce the editor's screen organization, tool placement, object manipulation, contextual controls, and complete user-facing feature set as closely as practical, while retaining OSS branding and original code/assets. The [PixelLab Feature Parity Matrix](15-PIXELLAB-FEATURE-PARITY.md) is a release acceptance requirement; the checklist below is a minimum, not an exhaustive cap.

- Canvas is the dominant work area and scales to the available editor space.
- Canvas remains continuously visible in the main editor workspace; tool and property controls live in a bottom horizontal rail that can be swiped left/right, not in a long vertical stack that pushes the canvas off-screen.
- Long contextual panels may scroll vertically inside their own bounded panel, but the canvas itself must remain visible while those panels are used.
- Canvas logical aspect ratio follows the selected LIVE output orientation, not the phone's physical display orientation.
- Initial canvas is empty.
- Portrait (9:16) and Landscape (16:9) are the only initial canvas modes.
- LIVE settings provide the orientation selector. The Scene Editor displays the resulting canvas and does not provide independent ratio presets, custom width/height fields, or per-scene aspect-ratio settings.
- The editor canvas remains a composition surface for LIVE output. Share Screen is inserted as a source/element from the element shelf and can be transformed like other supported sources.
- Orientation changes are made while LIVE is stopped; the app explains that all scenes in the active project will be converted. The conversion preserves composition proportionally where possible and is undoable/recoverable.
- Bottom tool rail presents the element catalog and installed elements; choosing an uninstalled element starts an on-demand install flow with a clear confirmation before download. Do not download the full catalog automatically.
- Selecting an object exposes contextual tools in the same tool area, following PixelLab's editing flow rather than sending users through unrelated settings pages.
- Contextual tools include transform, crop, opacity, style, arrangement, and source-specific settings; text controls include the agreed font, color, size, weight/style, alignment, outline, shadow, and related typography controls.
- Include layer list with reorder, visibility, lock, duplicate, group, delete; undo/redo; alignment; grid/snap; guides; safe areas; and asset management.
- Touch interactions must support direct selection, drag, corner resize, rotation handles, and one-handed operation with clear selection handles. Preserve the full PixelLab feature-parity target; do not treat this minimum list as exhaustive.
- Editor UI may rotate to suit the selected LIVE orientation where supported; viewport rotation/zoom/pan must not alter logical scene geometry.

## Audio Mixer
Dedicated page with visible meters and individual channel strips. Each source exposes only controls it supports: gain/volume, mute, balance, routing, and source status. LIVE and MONITOR buses are explicit. Do not silently replace unavailable sources.

## Extension Center
Show extension name, author/provenance, version, compatibility, size, permissions, license, update state, and install/remove controls. Explain permission requests before enabling. Every user-addable scene element type (including Text, Image, Shape, Sticker, Emoji, Drawing, GIF, Video, Template, and Share Screen) is represented by an extension. Download only when the user requests an uninstalled element, and ask before downloading. Beta builds may bundle the exact test extensions required.

## Settings
All global preferences belong here: account, language, appearance, accent/theme customization, full-screen/system-bar behavior, storage/cache, backups, global streaming defaults, module permissions/update policy, and diagnostics.

## Visual design
- Modern, compact, touch-friendly, and low-motion.
- Light and dark themes.
- User-selectable accent colors; later theme editor can add palette management and advanced color controls.
- Avoid a single fixed accent color and avoid heavy decorative animations.
- Respect Android display cutouts/system bars; provide Auto, normal bars, and immersive options where supported.
- Accessibility: readable contrast, scalable text, clear focus/selection states, and adequate touch targets.

## Interaction quality
Use autosave with visible save/recovery state. Confirm destructive operations and risky permission changes, not routine navigation or every small edit. Provide undo for reversible editing operations.
