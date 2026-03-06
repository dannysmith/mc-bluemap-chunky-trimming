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

## Progress

### Phase 1: Project Setup & Chunk Scanner — DONE

All code compiles and the shadow JAR builds (121KB).

**What was built:**

| File | Status | Notes |
|------|--------|-------|
| `build.gradle` + `settings.gradle` | Done | Gradle (not Maven — Maven wasn't available). Java 21 target (BlueMapAPI 2.7.7 requires it). Shadow plugin with Querz NBT relocated to `dev.danny.chunktrimmer.lib.querz`. |
| `bluemap.addon.json` | Done | id: `chunk-trimmer`, entrypoint: `dev.danny.chunktrimmer.ChunkTrimmerAddon` |
| `ChunkTrimmerAddon.java` | Done | Entrypoint. Registers onEnable/onDisable. Loads config + cached scan, kicks off background rescan thread. Has `TODO` comments where Phase 2 overlay code plugs in. |
| `Config.java` | Done | JSON config (not HOCON — kept it simple). Settings: `worldRegionPath` (override), `overlayY`, `extraPlayerBlocks`. |
| `ChunkAnalysis.java` | Done | Java record with helper methods (`inhabitedTimeFormatted()`, `key()`, etc.) |
| `BlockClassifier.java` | Done | 150+ block IDs in default set. Covers storage, crafting, redstone, rails, signs, concrete, glazed terracotta, stripped logs, glass, torches, etc. |
| `RegionFileReader.java` | Done | **Custom .mca reader** — reads raw region file bytes, decompresses chunks, parses NBT via `NBTDeserializer`. Completely bypasses Querz NBT's broken `MCAFile`/`Chunk` classes. |
| `ChunkScanner.java` | Done | Iterates region files via RegionFileReader. Branches on `DataVersion >= 2844` for 1.18+ format. Extracts InhabitedTime, scans block palettes (palette-only, no block data), counts tile entities. |
| `ScanResult.java` | Done | Wrapper with summary stats (`chunksWithActivity()`, `chunksWithPlayerBlocks()`) |
| `ScanCache.java` | Done | JSON persistence with compact keys (`it`, `pb`, `te`, `pbt`). Custom Gson adapter. |

**Key decisions made during implementation:**
- **Gradle instead of Maven** — Maven wasn't installed and couldn't be installed (Xcode license issue). Gradle wrapper generated from a downloaded distribution.
- **Java 21** — BlueMapAPI 2.7.7's Gradle module metadata declares `jvm.version: 21`. Java 17 target caused dependency resolution failure.
- **Custom region file reader** — Instead of using Querz NBT's `MCAFile`/`Chunk` classes (which silently break on 1.18+ worlds), we read region files at the byte level and parse raw NBT via `NBTDeserializer`. This is ~80 lines and completely avoids the compatibility issue.
- **JSON config instead of HOCON** — Avoided the Configurate dependency. Simple Gson-based JSON config.
- **No BMUtils dependency** — Config directory resolved manually from `api.getWebApp().getWebRoot().getParent()`. Kept dependency count minimal.
- **`BlueMapWorld.getSaveFolder()` returns `Path` not `Optional<Path>`** in 2.7.7 — wrapped in try-catch instead.

### Phase 2: BlueMap Overlays — DONE

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
  - `Shape.createRect(new Vector2d(chunkX*16, chunkZ*16), new Vector2d((chunkX+1)*16, (chunkZ+1)*16))`
  - `depthTestEnabled(false)` so always visible
  - Label: chunk coords + formatted inhabited time
- Skip chunks with InhabitedTime = 0 (vast majority — biggest perf optimization)
- Create markers on background thread (BlueMapAPI is thread-safe)

**2.5 DataExporter**
- Writes compact JSON to BlueMap's `AssetStorage` per map
- Format: `{ "scanTimestamp": ..., "world": "...", "chunks": { "x,z": { "it": ticks, "te": count, "pb": bool }, ... } }`
- Web addon fetches this file on load

**Where to plug in:** `ChunkTrimmerAddon.java` has two `TODO` comments marking where overlay code goes — one after loading cached data (instant overlays) and one after the background rescan completes (refresh overlays).

### Phase 3: Web Addon — Interactive Selection — TODO

**3.1 Register scripts**
- Java addon copies `chunk-trimmer.js` and `chunk-trimmer.css` from JAR resources to web root
- Registers via `api.getWebApp().registerScript()` and `registerStyle()` in `onEnable`

**3.2 UI elements**
- "Chunk Trimmer" toggle button in BlueMap's control bar
- When active: shows overlay layers, selection count, Clear/Export buttons
- Shows chunk info on hover (from fetched scan data)
- Ctrl/Cmd+click handler for chunk selection

**3.3 Click handling**
- Listen for `bluemapMapInteraction` event
- Check for `ctrlKey || metaKey` modifier
- Convert world coords to chunk: `Math.floor(x / 16)`, `Math.floor(z / 16)`
- Toggle chunk in selection state
- `event.preventDefault()` when modifier held to prevent map panning

**3.4 Selection visualization**
- Create Three.js `Mesh` objects (PlaneGeometry) for selected chunks
- Distinct color (red/orange with strong border)
- Add to / remove from Three.js scene as chunks are selected/deselected

**3.5 Selection state**
- Stored in `localStorage` keyed by world name
- Format: `{ "world": "world", "chunks": [[x,z], [x,z], ...] }`
- Loaded on page load, saved on every selection change

### Phase 4: Export — TODO

**4.1 JSON export** — Client-side download via Blob + URL.createObjectURL
**4.2 CSV export** — Simple `chunkX,chunkZ` per line (MCA Selector compatible)

## Build & Test

```bash
cd bluemap-chunk-trimmer
./gradlew shadowJar          # builds to build/libs/bluemap-chunk-trimmer-0.1.0.jar
./gradlew compileJava        # compile check only
```

## Verification

1. **Test with real .mca files**: Still needs to be done. Read a real region file from the server, verify InhabitedTime values are sensible and non-zero near builds.

2. **Integration test**: Drop JAR in BlueMap's `packs/` folder, verify overlays appear.

3. **Performance**: Test with large world (100k+ chunks).
