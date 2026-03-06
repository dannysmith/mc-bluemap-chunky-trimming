# Task 1: Overlay Dimension Filtering & Position Bugs

## Bug 1: Overlays display on all maps regardless of dimension — FIXED

**Root cause:** `OverlayManager.update()` iterated `api.getMaps()` and applied identical marker sets to every map. Additionally, `ChunkTrimmerAddon.resolveRegionDir()` only scanned one dimension (whichever was found first).

**Fix (commit 0b5256b):**
- `ChunkTrimmerAddon` now iterates all `api.getWorlds()`, scans each world's region dir, produces `Map<BlueMapWorld, ScanResult>`
- `OverlayManager.update()` uses `world.getMaps()` to apply markers only to maps belonging to each world
- `ScanCache` stores per-world cache files (`scan-cache-{worldId}.json`)
- `DataExporter` exports world-keyed JSON structure
- Web addon flattens all world chunks for export enrichment
- Removed `worldRegionPath` config option (auto-detect all worlds)

---

## Bug 2: Player-modified markers have many false positives — OPEN

**Status:** Confirmed as false positives from naturally-generated blocks in structures, not a coordinate bug. The `BlockClassifier` player-block list includes blocks that appear naturally in villages, temples, mineshafts, etc. (e.g. `minecraft:chest`, `minecraft:torch`, `minecraft:rail`, `minecraft:brewing_stand`).

**Possible approaches (not yet implemented):**
- Require `inhabitedTime > threshold` alongside player blocks to filter out unvisited structures
- Exclude blocks common in generated structures from the default list
- Use a tiered classification (strong signals like concrete/shulker boxes vs weak signals like chests/torches)
