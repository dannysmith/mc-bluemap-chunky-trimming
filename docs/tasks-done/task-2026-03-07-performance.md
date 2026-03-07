# Task: Performance

At the moment I'm manually testing this on a map with a very small explored area. This plug-in may well be used on much much much much much much much much much much much larger maps with much more areas explored and inhabited. So I would like to do a thorough review of how we can make this as performant as possible. I feel like that probably wants to include looking at a few things:

1. The chunk-scanning part which happens when Bluemap is loaded on the server
  - Can we make this fundamentally more performant at all?
  - Can we somehow persist the data from the last run and only update things which have changed? I suspect this will be impossible since in order to know what's changed we still need to read all of the chunks anyway, right? In order to get their player inhabited time now etc.
2. Heatmap markers - I think we currently use bluemaps built-in markers to render the inhabitedtime and player-modified "heatmaps". This works great and is definitely the right solution here. But is there any way we can possibly make this a little more performant using some of Blue Maps features? I'm conscious that in very large worlds where we've inhabited a lot of the terrain. Blue map is going to be drawing potentially thousands and thousands of these square markers when we have these heat maps on.
3. Chunk Selector Overlay - Our chunk selector does not use BlueMaps standard markers for good reasons. Is there any way that we can make this layer i.e. rendering those more performant at all? 
4. Chunk Selector Web Performance - Our chunk selector also includes a bunch of other front end stuff, including the pop up in the bottom right. And I assume a bunch of JavaScript actions and things which occur to store this stuff when you click to select or deselect chunks. Can we look at this from the point of view of a web performance expert? And is there anything we can do to improve the performance of this stuff? I'm particularly interested in anything we can do with our vanilla JavaScript and potentially CSS, but probably JavaScript, to make sure that we are doing this in a sensible performant way in the browser.
5. General performance improvements - When we've addressed all of these other things, we should probably also look for any other general performance improvements that we could possibly make. I suspect most of these will be to do with how we use Blue Maps APIs and how we use JavaScript, but it's probably worth when we've addressed everything else doing a quick general review of the entire code base here for any potential performance issues or bottlenecks. We should do this after we have done the preceding tasks. 

The first part of this task is looking at each of these in turn and reviewing the existing system for issues and areas for improvement. Then we can come up with a plan for addressing the most important ones.

---

## Review Findings

### 1. Chunk Scanning

**Current approach:** Single-threaded, sequential. Reads every `.mca` file with `RandomAccessFile`, decompresses every chunk's full NBT data via Querz `NBTDeserializer`, just to extract the single `InhabitedTime` long.

**Findings:**

- **Full NBT deserialization is overkill.** The entire chunk CompoundTag (sections, block states, entities, heightmaps, etc.) gets materialized in memory just to read one `long`. On large worlds this is the dominant cost. A minimal NBT scanner that finds `InhabitedTime` without building the full tree would be much faster, but is significant effort.
- **No parallelism.** Region files are independent — a thread pool would give near-linear speedup. The `ConcurrentHashMap` for results is already in place, suggesting this was anticipated.
- **Caching is already good.** Results are cached to JSON and loaded on startup, so the full scan only blocks first run / after restart. Compact keys (`x`, `z`, `it`). Solid.
- **Incremental scanning is feasible.** The region file header contains a per-chunk timestamp table (bytes 4096-8191) with last-modified times. We could store these in the cache and skip decompressing any chunk whose timestamp hasn't changed. `RegionFileReader` already reads past this table — it just doesn't use it. This would make re-scans nearly instant on mostly-unchanged worlds.

### 2. Heatmap Markers

**Current approach:** One `ShapeMarker` per chunk with ≥20 ticks activity. Unique interpolated color per marker.

**Findings:**

- On a well-explored server, every visited chunk gets a marker (potentially 50k+). But BlueMap's marker system is designed for large counts and uses viewport-based culling. Markers are `defaultHidden(true)` so they only render when explicitly toggled on.
- Each marker has a unique color (continuous interpolation), preventing easy merging of adjacent chunks. Quantizing to buckets and merging is complex with limited payoff.
- **Verdict: Likely fine as-is.** Main lever if needed later: raise the minimum threshold (currently 20 ticks) or quantize colors.

### 3. Chunk Selector Overlay (Three.js)

**Current approach:** One `THREE.Group` per selected chunk containing a `THREE.Mesh` (fill) and `THREE.LineSegments` (border). Shared geometry and material (good — enables draw call batching).

**Findings:**

- Shared geometry/material is correct. Three.js can batch when these match.
- But each chunk is still a separate Group/Mesh = separate draw calls. With hundreds of selections, that's hundreds of draw calls. `InstancedMesh` would render all chunks in a single draw call via per-instance transforms.
- Rectangle preview creates/destroys geometry every pointer move during drag — minor, only during active drag.
- **Verdict: Fine for typical use (dozens to low hundreds of chunks). `InstancedMesh` would matter for 1000+ selections.**

### 4. Web Performance (JS/DOM)

**Current approach:** Vanilla JS IIFE, localStorage persistence, 500ms polling.

**Findings:**

- **`worldPosFromMouse` allocates on every call.** Creates new `Vector2`, `Raycaster`, `Vector3` each time. Called on every `mousemove` (HUD) and every `pointermove` (paint drag). Should be pre-allocated module-level singletons to reduce GC pressure.
- **`onHoverMove` runs on every global mousemove.** Calls `getBoundingClientRect()` each time. Could throttle or cache the rect, but chunk-boundary-crossing is already a natural throttle for the innerHTML update.
- **500ms polling** walks the Three.js scene graph every cycle (`checkHeatmapVisible`, `setOurMarkersVisible`). Not horrible at 2Hz but could listen for `hashchange` instead of polling `isFlatView`.
- **`saveSelection` during paint-drag** is correctly batched (only on pointerup).
- **Verdict: Pre-allocating Three.js math objects is the clear quick win. Everything else is minor.**

### 5. General

- `DataExporter.buildJson` uses StringBuilder and skips zero-activity chunks — efficient.
- Cache serialization uses custom adapter (no reflection) — good.
- `scanData` in the web addon holds the full chunk map in memory but even 100k chunks is only a few MB — fine.
- No other significant issues found.

---

## Implementation Plan

Based on the review, three items are worth implementing:

### A. Parallel Region File Scanning -- DONE

**What:** Add a thread pool to `ChunkScanner.scan()` so region files are processed concurrently.

**Changes:**
- `ChunkScanner.java`: Replaced the sequential `for` loop over region files with an `ExecutorService` (fixed thread pool, sized to available processors). Each region file submitted as a task. Results collected into existing `ConcurrentHashMap`. Progress logging uses `AtomicInteger` for thread safety.

### B. Timestamp-based Incremental Scanning -- DONE

**What:** Store per-chunk region timestamps from the `.mca` header in the cache. On re-scan, read the timestamp table first and skip decompressing any chunk whose timestamp matches the cached value.

**Changes:**
- `RegionFileReader.java`: Added `readTimestamps()` method that reads the timestamp table (bytes 4096-8191) and returns per-chunk timestamps as an int array.
- `ChunkAnalysis.java`: Added `regionTimestamp` field to the record.
- `ScanCache.java`: Serializes/deserializes `regionTimestamp` as `"ts"` key in compact adapter. Backwards-compatible (defaults to 0 if absent in old cache files).
- `ChunkScanner.java`: `scan()` now accepts `previousResults`. For each chunk, compares its region timestamp against the cached value — if unchanged, reuses the cached `ChunkAnalysis` without decompressing NBT. Logs how many chunks were skipped vs re-scanned.
- `ChunkTrimmerAddon.java`: Passes loaded cache into the scanner for each world.

### C. Pre-allocate Three.js Objects in `worldPosFromMouse` -- DONE

**What:** Move `Vector2`, `Raycaster`, `Vector3` allocations out of `worldPosFromMouse` to module-level singletons, reused on every call.

**Changes:**
- `chunk-trimmer.js`: Created `_mouseVec`, `_raycaster`, `_intersection` at module scope. `worldPosFromMouse` reuses them via `.set()` instead of allocating new objects per call.
