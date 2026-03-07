# Task: Minor Improvements

## 1 - Inhabited time HUD - DONE

- [x] Move it to bottom center of the screen
- [x] Ensure it only shows when the inhabited time markers are toggled on

## 2 - Selected Chunks: Hatched Pattern - DONE

Replaced solid orange fill with cyan diagonal stripe canvas texture. Border color also changed to cyan. Visually distinct from all heatmap colors.

## 3 - Enable only in "flat" mode - DONE

Detects view mode via URL hash (`#flat:`, `#perspective:`, `#free:`). When not in flat mode:
- Toggle button and spacer hidden from control bar
- Panel hidden, overlay disabled, selection meshes cleared
- Heatmap and Player Modified marker sets force-hidden (re-enforced each poll cycle)
- HUD hidden

All restored automatically when switching back to flat mode.
