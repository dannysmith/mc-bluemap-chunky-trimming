# Task 1: Overlay Dimension Filtering & Position Bugs

## Bug 1: Overlays display on all maps regardless of dimension

**Severity:** High — makes the addon unusable for multi-dimension servers.

**Root cause:** `OverlayManager.update()` (line 90-95) iterates `api.getMaps()` and applies identical marker sets to every map. There is no filtering to match scan data to the correct BlueMap world/dimension.

Additionally, `ChunkTrimmerAddon.resolveRegionDir()` only scans **one** dimension (whichever `api.getWorlds()` returns first with a `region/` directory). So End data gets shown on the Overworld map, Overworld data on the End map, etc.

**Observed:** End dimension inhabited-time heatmap (blue chunks) appears in the bottom-right of the Overworld map where there is no rendered terrain.

### Fix plan

1. **Scan all dimensions, not just the first found.** Change `resolveRegionDir()` to return a map of `BlueMapWorld -> Path` (one region dir per world). The scanner should produce a `ScanResult` per world.

2. **Filter overlays per map.** In `OverlayManager.update()`, use `BlueMapMap.getWorld()` to match each map to its world's scan results. Only add markers from the matching `ScanResult`.

3. **Update DataExporter** to export per-world data (e.g. keyed by world ID in the JSON), so the web addon can also filter correctly.

**Files to change:**
- `ChunkTrimmerAddon.java` — scan loop per world
- `OverlayManager.java` — accept world filter, apply markers only to matching maps
- `ScanResult.java` — may need a world identifier field
- `DataExporter.java` — per-world JSON structure
- `ScanCache.java` — cache per world

---

## Bug 2: Player-modified markers (yellow) appear in wrong location

**Severity:** Medium — likely resolves itself once Bug 1 is fixed.

**Analysis:** The coordinate math is correct throughout the pipeline:
- `RegionFileReader` parses the location table with `index = (z*32+x)*4` matching the Minecraft spec
- Absolute chunk coords `regionCoord * 32 + localCoord` are correct
- `OverlayManager.createChunkMarker()` converts with `chunk * 16` correctly
- Both heatmap and modified markers use the same `createChunkMarker()` method

The four yellow markers on the Overworld map are almost certainly **End city chunks** containing naturally-generated blocks that are in the `BlockClassifier` player-block list (`minecraft:end_rod`, `minecraft:chest`, `minecraft:brewing_stand`). Their coordinates are correct for the outer End islands (~1000+ blocks from origin) but appear meaningless when projected onto the Overworld map via Bug 1.

**After fixing Bug 1:** Verify whether the yellow markers look correct on the End map. If they still seem wrong, the issue is false positives from End city blocks in `BlockClassifier`, not a coordinate bug.

**Possible follow-up:** Consider excluding certain blocks from the player-block list when scanning non-overworld dimensions, or add a secondary heuristic (e.g. require `inhabitedTime > threshold` alongside player blocks) to reduce false positives from generated structures.

---

## Implementation order

1. Refactor scanning to iterate all worlds (produces `Map<BlueMapWorld, ScanResult>`)
2. Refactor `OverlayManager.update()` to filter markers per map's world
3. Update `DataExporter` and web addon for per-world data
4. Update `ScanCache` for per-world caching
5. Test on server, verify overlays are dimension-correct
6. Reassess Bug 2 — if yellow markers still look wrong on the End map, investigate `BlockClassifier` false positives
