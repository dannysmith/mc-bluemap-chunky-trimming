# Task: Minor Improvements

## 1 - Inhabited time HUD

- [ ] Move it to bottom center of the screen
- [ ] Ensure it only shows when the inhabited time markers are toggled on

## 2 - Selected Chunks: Hatched Pattern

Replace the solid orange fill on selected chunks with a diagonal hatched/striped pattern. This makes selections visually distinct from heatmap colors regardless of what overlays are active. No need to change border thickness — the hatching alone is sufficient differentiation.

Approach: Generate a canvas-based stripe texture and use it as a `map` on the selection material.

## 3 - Enable only in "flat" mode

Currently our chunk selection and our two heatmap overlays work in all view modes (perspective, flat and free/spectator). But the markers are chunk-based and don't line up properly in any view except "flat". We should:

1. Only enable the chunk selector in flat mode, including the toggle button. Gracefully hide everything if the user switches away from flat mode, restore when they switch back.
2. Only show heatmap markers when in flat mode, even if toggled on. Look for an idiomatic BlueMap way of doing this if one exists.
