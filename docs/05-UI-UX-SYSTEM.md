# OSS — UI/UX System

## Navigation
Primary destinations: Home, Studio, Scene Editor, Audio Mixer, Extension Center, Settings. Keep navigation predictable and shallow. LIVE session state is global and remains active when changing pages.

## Home
Home is a true dashboard: recent projects, recent activity, shortcuts, extension updates, and project recovery notices. Do not turn Home into the LIVE control surface.

## Studio
Studio is the central LIVE operations page. It contains destination setup/status, stream quality, scene selection, quick audio controls, connection health, LIVE statistics, and Go Live / End Live. Detailed channel configuration belongs in Audio Mixer; global app preferences belong in Settings.

Each destination displays its own state and error. The primary action changes from Go Live to End Live when the session is active. Ending one destination must not imply ending all destinations unless the user chooses that action.

## Scene Editor
The editor should closely reproduce the usability and interaction conventions of PixelLab while retaining OSS identity and original implementation/assets.

- Canvas is the dominant work area and scales to the available editor space.
- Canvas logical aspect ratio is independent from phone display orientation.
- Initial canvas is empty.
- Portrait and Landscape canvas modes only.
- Canvas dimensions/ratio controls live in a dedicated canvas settings menu.
- Bottom tool rail presents installed elements/modules; selecting an element opens contextual tools in the same tool area.
- Contextual tools include transform, crop, opacity, style, arrangement, and source-specific settings.
- Include layer list, undo/redo, duplicate, lock, delete, group, grid/snap, safe-area guides, and asset manager.
- Touch interactions must support direct drag/resize/rotate with clear selection handles.
- Editor UI orientation follows the selected editor orientation; it must not distort scene geometry.

## Audio Mixer
Dedicated page with visible meters and individual channel strips. Each source exposes only controls it supports: gain/volume, mute, balance, routing, and source status. LIVE and MONITOR buses are explicit. Do not silently replace unavailable sources.

## Extension Center
Show extension name, author/provenance, version, compatibility, size, permissions, license, update state, and install/remove controls. Explain permission requests before enabling.

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
