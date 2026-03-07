# Task: Chunk Selector Dimensions

The chunk selector does not currently differentiate between different dimensions. If I select some chunks in the overworld and then switch my map to the nether, I still see those chunks as selected. And vice versa. Obviously this is the same with the end dimension.

Selected chunks should be scoped to the relevant dimension.

This doesn't just apply to how they're visually displayed also when I go to export them as JSON/CSV, The export just includes all of them, with no indication of which dimension they're in.

Now the simplest solution to this would simply be to clear the chunk selection whenever we switch to a new dimension And assume that when a user is selecting chunks in order to export them for trimming. But they will do it one dimension at a time. The downside to this is that in some cases we will have multiple maps for the same dimension. For example, on many of my servers, I have two blusmap maps for the Nether: one for the nether roof and one for "below". And I probably want to be able to switch between these when selecting chunks and have those chunks persist between those map switches because they're both just different views of the same dimension. So like I would probably start selecting blocks to trim on the nether roof and then I would go and swap to the map for lower down in the nether and check that I hadn't selected any blocks that I shouldn't have. You could imagine a similar situation in the overworld where I might have one map for the surface and another map for a certain level underground. And I would want to swap between them. So what I'm really saying here is that these should be per dimension, not necessarily per bluemap map.

The final thing here is that when we have the JSON export, I feel like we should include the following in the JSON itself when it's exported at the top level:

- Dimension
- World Seed
- (if possible) Name of the world

And then we'll include the chunk data.

---

## Implementation Plan

### Foundation already in place

The recent HUD world-awareness work (`15664ea`) added infrastructure we can build on:
- `data.json` now includes `mapIds` per world (the list of BlueMap map IDs belonging to each world)
- The web addon builds a `mapToWorld` index mapping `mapId → worldId`
- `getChunkData(key)` resolves the current map to its world via `app.mapViewer.map.data.id`

### Step 1: Per-world selection storage (JS)

**Current state:** `selection` is a flat `Set<string>` stored in localStorage under `"chunk-trimmer-selection"`.

**Change to:** A world-keyed structure. Keep an in-memory map of `worldId → Set<string>` and persist the whole thing:

```json
{
  "overworld": ["0,0", "1,1", "5,-3"],
  "the_nether": ["10,4"],
  "the_end": []
}
```

- `getCurrentWorldId()` — resolves `app.mapViewer.map.data.id` via `scanData.mapToWorld` (already have this logic in `getChunkData`)
- When toggling a chunk, operate on `selections[currentWorldId]`
- On save, write the full world-keyed object to localStorage
- On load, parse the world-keyed object; if old flat format detected, migrate it to the current world (or discard)

### Step 2: Map switch detection (JS)

Detect when the user switches maps and, if the world changed, swap the active selection.

**Approach:** `setInterval` (~500ms) polling `app.mapViewer.map.data.id`:
- Cache the last-seen map ID
- On change: resolve both old and new map IDs to world IDs
- If world changed: save current selection, load new world's selection, rebuild meshes
- If same world (e.g. switching between nether roof/below maps): do nothing — selection persists

This is lightweight and avoids needing to hook into BlueMap's internal navigation.

**Panel update:** Show the current dimension name in the panel header (e.g. "Chunk Trimmer — Overworld") so the user knows which dimension's selection they're editing.

### Step 3: Export metadata (JS)

Update `exportJSON()` to include top-level metadata from `scanData.worlds[currentWorldId]`:

```json
{
  "dimension": "overworld",
  "worldName": "world",
  "worldSeed": 1234567890,
  "chunks": [...]
}
```

`dimension` and `worldName` are already available in the scan data. `worldSeed` depends on Step 4.

### Step 4: World seed extraction (Java) — optional

BlueMap's API does not expose the world seed. But we have `world.getSaveFolder()` (gives the dimension's save directory) and the Querz NBT library.

**Approach:**
- From `getSaveFolder()`, look for `level.dat` in that directory, then parent directory (handles both vanilla `world/` and nether `world/DIM-1/` layouts)
- Read with `NBTDeserializer` (gzipped), extract seed from `Data.WorldGenSettings.seed` (1.16+) with fallback to `Data.RandomSeed` (older)
- Add `seed` field to `ScanResult`, include in `data.json` export
- ~20-30 lines of Java, low complexity, with graceful fallback (seed = null if level.dat not found or unreadable)

### Files to modify

**Java (only if doing seed extraction):**
- `ScanResult.java` — add `seed` field (or keep it in a separate record)
- `ChunkTrimmerAddon.java` — read seed from level.dat after resolving region dirs
- `DataExporter.java` — include `"seed":` in JSON output per world

**JS (main work):**
- `chunk-trimmer.js`:
  - `selection` becomes `Map<worldId, Set<string>>` (or just a plain object)
  - New `getCurrentWorldId()` helper (extract from existing `getChunkData` logic)
  - New `setInterval` for map switch detection
  - `saveSelection` / `loadSelection` — world-keyed format
  - `toggleChunk`, `clearSelectionMeshes`, `rebuildAllMeshes` — scope to current world
  - `exportJSON` / `exportCSV` — include dimension metadata, only export current world's selection
  - Panel: show dimension name, update count for current world only

### Edge cases to handle
- **No scan data loaded yet:** fall back to using map ID as the world key (won't match across maps of same world, but won't crash)
- **Old localStorage format:** detect flat array format and either migrate to current world or discard
- **Map switch while selection mode is off:** still track the world change so when toggled back on, the right selection loads
