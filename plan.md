# Implementation Plan: BlueMap Chunk Trimmer Addon

## Context

Danny has a Minecraft server with years of accumulated chunks and no trimming. He wants a BlueMap addon that shows player activity per chunk, detects player-modified chunks, and lets you visually select chunks for trimming — all from the BlueMap web interface.

Nothing like this exists for any Minecraft web map platform. The APIs are well-suited to the job. See `research.md` for full findings.

## Scope (v1)

Build visualization and selection layers only. No deletion/trimming integration yet.

1. **InhabitedTime heatmap overlay** — color-coded chunks on the map
2. **Player-modified overlay** — block palette + tile entity heuristics
3. **Interactive chunk selection** — Ctrl/Cmd+click to select/deselect
4. **Persist & export selections** — save chunk coordinates for future deletion (method TBD)

## Architecture

**BlueMap Native Addon** (Java JAR in `packs/`):
- Scans `.mca` region files via Querz NBT (raw CompoundTag access for 1.18+ compat)
- Caches scan results to JSON
- Creates ShapeMarker overlays in toggleable MarkerSets
- Writes chunk analysis data to BlueMap's AssetStorage for the web addon to fetch

**Companion Web Addon** (JavaScript, bundled in the same JAR):
- Adds Chunk Trimmer toggle + panel to BlueMap UI
- Ctrl/Cmd+click to select/deselect chunks (using Three.js overlays)
- Selection stored in localStorage, exportable as JSON/CSV download
- Fetches scan data from AssetStorage JSON file

## Critical Technical Detail: Querz NBT + 1.18+

The Querz NBT `Chunk` class convenience methods (`getInhabitedTime()`, etc.) assume pre-1.18 format (data under a `Level` wrapper tag). **On 1.18+ worlds, these silently return 0 for everything.**

Solution: Use `LoadFlags.RAW` to get raw `CompoundTag`, then branch on `DataVersion`:

```java
CompoundTag data = chunk.getHandle();
int dataVersion = data.getInt("DataVersion");

if (dataVersion >= 2844) {
    // 1.18+: no Level wrapper
    inhabitedTime = data.getLong("InhabitedTime");
    sections = data.getListTag("sections");
    blockEntities = data.getListTag("block_entities");
} else {
    // Pre-1.18: Level wrapper
    CompoundTag level = data.getCompoundTag("Level");
    inhabitedTime = level.getLong("InhabitedTime");
    sections = level.getListTag("Sections");
    blockEntities = level.getListTag("TileEntities");
}
```

**This must be validated early** — build a test harness against a real .mca file from the server before building overlays.

## Project Structure

```
bluemap-chunk-trimmer/
├── pom.xml
├── src/main/java/dev/danny/chunktrimmer/
│   ├── ChunkTrimmerAddon.java        # Entrypoint (Runnable)
│   ├── Config.java                    # Configuration (HOCON via Configurate)
│   ├── scanner/
│   │   ├── ChunkScanner.java         # Iterates .mca files, extracts data
│   │   ├── ChunkAnalysis.java        # Per-chunk data record
│   │   ├── BlockClassifier.java      # Player-block detection set
│   │   └── ScanCache.java            # JSON serialization of scan results
│   ├── overlay/
│   │   ├── OverlayManager.java       # Creates/manages MarkerSets
│   │   ├── HeatmapColors.java        # InhabitedTime → Color mapping
│   │   └── ModifiedColors.java       # Player-modified → Color mapping
│   └── web/
│       └── DataExporter.java         # Writes scan JSON to AssetStorage
├── src/main/resources/
│   ├── bluemap.addon.json
│   ├── default-config.conf           # Default HOCON config
│   └── web/
│       ├── chunk-trimmer.js
│       └── chunk-trimmer.css
```

## Dependencies

| Dependency | Coordinates | Version | Scope | Notes |
|-----------|------------|---------|-------|-------|
| BlueMapAPI | `de.bluecolored:bluemap-api` | 2.7.7 | provided | |
| BMUtils | `com.technicjelle:BMUtils` | latest | compile | Config directory, utilities |
| Querz NBT | `com.github.Querz:NBT` | 6.1 | compile (shaded) | JitPack repo. Must relocate to avoid conflicts |
| Gson | (bundled with BlueMap) | | provided | For JSON serialization |

Repos needed: `repo.bluecolored.de/releases`, `jitpack.io`

## Implementation Phases

### Phase 1: Project Setup & Chunk Scanner

**1.1 Scaffold the project**
- Maven project following BlueMapNativeAddonTemplate
- `pom.xml` with all dependencies, maven-shade-plugin with Querz NBT relocation
- `bluemap.addon.json` with id `chunk-trimmer` and entrypoint class

**1.2 ChunkTrimmerAddon entrypoint**
- Implements `Runnable`
- In `run()`: register `BlueMapAPI.onEnable()` and `onDisable()` callbacks
- `onEnable`: load config, load cached scan data, create overlays, kick off background rescan
- `onDisable`: cleanup

**1.3 Config**
- HOCON config in addon's config directory (BMUtils `BMNConfigDirectory`)
- Settings: world path override (default: auto-detect from `BlueMapWorld.getSaveFolder()`), heatmap thresholds, player-block list, overlay Y position

**1.4 ChunkScanner**
- Find all `r.*.*.mca` files in `<world>/region/`
- For each region file, iterate 32x32 chunk slots
- Use `LoadFlags.RAW` to get raw CompoundTag
- Extract per chunk:
  - `InhabitedTime` (long, ticks)
  - Block palette entries: navigate sections -> block_states -> palette -> Name strings. Only need palette, not the packed block data array
  - Tile entity count from `block_entities` list length
  - Chunk coordinates (xPos, zPos)
- Handle both pre-1.18 and 1.18+ NBT structure via DataVersion check
- Wrap individual chunk reads in try-catch (region files may be mid-write by server)
- Run on background thread

**1.5 BlockClassifier**
- Maintains a `Set<String>` of block IDs considered player-placed
- Default set: chests, crafting tables, furnaces, signs, rails, redstone components, concrete, stripped logs, beacons, scaffolding, etc.
- Configurable via config file
- Returns boolean for "any palette entry matches"

**1.6 ChunkAnalysis data record**
- `chunkX`, `chunkZ` (int)
- `inhabitedTime` (long, ticks)
- `hasPlayerBlocks` (boolean)
- `playerBlockTypes` (Set<String>, only the detected ones)
- `tileEntityCount` (int)

**1.7 ScanCache**
- Serialize scan results to `scan-cache.json` in config directory
- Load on startup for instant overlay creation
- Include scan timestamp and world name
- Background rescan overwrites cache when complete

### Phase 2: BlueMap Overlays

**2.1 OverlayManager**
- Creates three MarkerSets on each BlueMap map:
  - `"chunk-trimmer-heatmap"` — "Inhabited Time" — toggleable, default hidden
  - `"chunk-trimmer-modified"` — "Player Modified" — toggleable, default hidden
  - `"chunk-trimmer-selection"` — "Selected for Trimming" — toggleable, default visible
- Populates heatmap and modified MarkerSets from scan data
- Selection MarkerSet updated when selection changes

**2.2 HeatmapColors**
- Maps InhabitedTime (ticks) to Color with configurable thresholds
- Default gradient:
  - < 1200 ticks (1 min): faint cool blue, low opacity
  - 1200-12000 (1-10 min): teal/cyan
  - 12000-72000 (10-60 min): yellow/orange
  - 72000+ (1+ hour): red, higher opacity
- Linear interpolation between thresholds

**2.3 ModifiedColors**
- Combines signals into confidence levels:
  - `hasPlayerBlocks && inhabitedTime > threshold`: green (definitely keep)
  - `hasPlayerBlocks || tileEntityCount > threshold`: yellow (uncertain)
  - Just `inhabitedTime > 0`: no overlay (explored but unmodified)

**2.4 Marker creation**
- One `ShapeMarker` per qualifying chunk:
  - `Shape.createRect(chunkX*16, chunkZ*16, (chunkX+1)*16, (chunkZ+1)*16)`
  - `depthTestEnabled(false)` so always visible
  - Label: chunk coords + formatted inhabited time
- Skip chunks with InhabitedTime = 0 (vast majority — biggest perf optimization)
- Create markers on background thread (BlueMapAPI is thread-safe)

**2.5 DataExporter**
- Writes compact JSON to BlueMap's `AssetStorage` per map
- Format: `{ "scanTimestamp": ..., "world": "...", "chunks": { "x,z": { "it": ticks, "te": count, "pb": bool }, ... } }`
- Web addon fetches this file on load

### Phase 3: Web Addon — Interactive Selection

**3.1 Register scripts**
- Java addon copies `chunk-trimmer.js` and `chunk-trimmer.css` from JAR resources to web root
- Registers via `api.getWebApp().registerScript()` and `registerStyle()` in `onEnable`

**3.2 UI elements**
- "Chunk Trimmer" toggle button in BlueMap's control bar
- When active:
  - Enables the overlay MarkerSets (make them visible)
  - Shows a small panel: selected count, "Clear" button, "Export JSON" button, "Export CSV" button
  - Shows chunk info on hover (from fetched scan data)
- Ctrl/Cmd+click handler for chunk selection

**3.3 Click handling**
- Listen for `bluemapMapInteraction` event (or equivalent click event)
- Check for `ctrlKey || metaKey` modifier
- Convert world coords to chunk: `Math.floor(x / 16)`, `Math.floor(z / 16)`
- Toggle chunk in selection state
- `event.preventDefault()` when modifier held to prevent map panning

**3.4 Selection visualization**
- Create Three.js `Mesh` objects (PlaneGeometry) for selected chunks
- Distinct color (e.g., red/orange with strong border) that stands out from heatmap colors
- Add to / remove from Three.js scene as chunks are selected/deselected

**3.5 Selection state**
- Stored in `localStorage` keyed by world name
- Format: `{ "world": "world", "chunks": [[x,z], [x,z], ...] }`
- Loaded on page load, saved on every selection change

### Phase 4: Export

**4.1 JSON export**
- Client-side download via Blob + URL.createObjectURL
- Format:
  ```json
  {
    "version": 1,
    "world": "world",
    "timestamp": "2026-03-05T12:00:00Z",
    "chunks": [{"x": 5, "z": -12}, {"x": 5, "z": -11}],
    "metadata": { "totalChunks": 2, "tool": "bluemap-chunk-trimmer" }
  }
  ```

**4.2 CSV export**
- Simple `chunkX,chunkZ` per line (compatible with MCA Selector format)
- Also client-side download

## Verification

1. **Test harness first**: Before any overlay work, read a real `.mca` file from the server. Dump raw NBT structure. Confirm InhabitedTime reads correctly for the server's MC version.

2. **Scanner test**: Point scanner at a world save directory. Verify JSON output has sensible InhabitedTime values (nonzero for chunks near spawn/bases, zero for wilderness).

3. **Manual integration test**:
   - Drop JAR in BlueMap's `packs/` folder
   - Start BlueMap (or `/bluemap reload`)
   - Open web UI — verify heatmap overlay appears in layer toggle
   - Enable heatmap — verify colored chunks appear near known builds
   - Enable player-modified overlay — verify it highlights expected chunks
   - Ctrl+click chunks — verify selection overlay appears
   - Export JSON — verify file contains correct chunk coordinates

4. **Performance**: Test with a large world. Monitor browser frame rate with heatmap overlay enabled and many markers visible.
