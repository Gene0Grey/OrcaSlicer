# Product

<!-- impeccable:product-schema 1 -->

Scope: the OrcaSlicer Android app in `android/` (Jetpack Compose over `liborca_core`, the shared libslic3r slicing core). The desktop wxWidgets app in `src/slic3r/GUI` is the source material, not this record's subject.

## Platform

android

## Users

Two groups, weighted equally:

- **Hobbyists at the printer**: home makers who downloaded a model and want to slice it on their phone near the printer: pick printer and filament, place the model, slice, send or save G-code. They need the short path to be obvious.
- **Power users away from the PC**: experienced OrcaSlicer users who expect the desktop's full depth (every process, filament and printer setting, per-object overrides, preview colour schemes) on a phone or tablet.

The quick path for casual prints and the full depth for experts must both be first-class; depth sits one tap away, never in the way.

## Product Purpose

A full OrcaSlicer on Android: the same slicing engine and system printer profiles as desktop OrcaSlicer 2.5.1, operated by touch. Success: a model goes from file to correct G-code on a phone, with results identical to the desktop for the same profile.

## Positioning

Not a remote control or a cloud slicer: the real OrcaSlicer engine (libslic3r) and the real vendor profiles run on the device, offline, with the desktop's settings layout extracted from its source.

## Operating Context

- Files arrive from the Android file picker (Downloads, Drive, model sites): STL, 3MF, OBJ, STEP.
- Output leaves as a saved or shared G-code file, or through the printer's own web UI (Mainsail, Fluidd, OctoPrint) in the Device tab.
- Used on phones held in one hand and on tablets, often near a printer; screens are touch-first, sometimes with gloves or dusty fingers.
- Desktop vocabulary is shared with users who also use the PC app: Home, Prepare, Preview, Device, Project; Printer, Filament, Process; Quality, Strength, Speed, Support, Multimaterial, Others; Slice plate, Export G-code.

## Capabilities and Constraints

- Implemented: vendor/printer picker over all bundled system profiles; preset select/save/discard; every setting from the desktop tabs (layout generated from `src/slic3r/GUI/Tab.cpp`); per-object overrides; 3D plate with orbit/pan/pinch, tap-select, move/rotate/scale gizmos, lay on face, arrange, orient, undo/redo, off-bed warning; slicing with progress; libvgcode G-code preview with legend, color schemes, layer and move sliders; export/share G-code; Device web view.
- Not yet implemented: 3MF project save/load with settings, G-code thumbnails, multi-plate, painting/cut/emboss tools, direct printer upload, Bambu networking (closed plugin, no Android build).
- Rendering of the 3D view is native GLES (`android/native/jni/Scene.cpp`); Compose draws everything around it.
- Settings labels, tooltips and option types come from the engine at runtime; the UI must render any option generically.

## Brand Commitments

- Name: OrcaSlicer. Keep the Orca identity: Orca teal as the brand accent and the desktop's terminology above.
- Everything else about the visual design is free to improve over the desktop.

## Evidence on Hand

- Desktop screenshots supplied by the user (Home, Prepare with Kobra 2 Neo profile, Preview with line-type legend).
- Real slicing output from the device (20 mm cube, Anycubic Kobra 2 Neo 0.4, 0.20mm Standard).
- No user research, testimonials or usage data exist; do not invent any.

## Product Principles

1. Same engine, same results: never diverge from desktop slicing behaviour for the sake of UI.
2. Fast path first, full depth one tap away.
3. The model is the hero: the 3D view gets the screen; controls stay out of its way.
4. Touch-native, not desktop-shrunk: targets for fingers, gestures over tiny widgets, nothing hover-only.
5. Speak the desktop's language so knowledge transfers both ways.

## Accessibility & Inclusion

Large touch targets (48 dp minimum), legible text at system font scaling, colour never the only signal (e.g. off-bed state also shows a message). The chosen board world is dark; whether a light variant is needed is an open decision the user has not been asked yet.
