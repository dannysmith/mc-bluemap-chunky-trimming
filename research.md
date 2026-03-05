# Research: BlueMap Chunk Trimming Helper

You've got a Minecraft world that's been lived in for a couple of years with zero chunk trimming. You want to see what's worth keeping, what's just explored wilderness, and get rid of the rest — all from BlueMap's web interface rather than faffing about with desktop tools.

The good news: **everything you need exists, nothing like this has been built, and the APIs are surprisingly well-suited to the job.** Here's everything I found.

---

## What BlueMap Actually Gives You to Work With

BlueMap has two addon systems, and you'd use both.

### Native Addons (Java)

These are JARs dropped into the `packs/` folder. They're platform-agnostic — same JAR works on Spigot, Paper, Fabric, Forge, NeoForge, Sponge, and even BlueMap's standalone CLI mode. No server mod required.

Each addon has a `bluemap.addon.json` descriptor and an entrypoint class implementing `Runnable`. You hook into the BlueMapAPI lifecycle:

```java
BlueMapAPI.onEnable(api -> {
    // Your addon starts here
});
BlueMapAPI.onDisable(api -> {
    // Cleanup
});
```

Reloads with `/bluemap reload`. Full filesystem access — meaning you can read `.mca` region files directly.

There's a [template project](https://github.com/TechnicJelle/BlueMapNativeAddonTemplate) and a [utility library (BMUtils)](https://github.com/TechnicJelle/BMUtils) to lean on.

### Web Addons (JavaScript/CSS)

JS/CSS files loaded after the map initialises. You get access to:

- `bluemap` — the BlueMapApp instance
- `BlueMap` — module namespace including Three.js and all marker types

You can add buttons, intercept clicks, manipulate the DOM, and access the Three.js scene directly. Custom events available: `bluemapCameraMoved`, `bluemapTileLoaded`, `bluemapMapInteraction`.

**The key bit**: a native addon can register web scripts via `webApp.registerScript("js/my-addon.js")`. So one addon ships both the backend processing AND the frontend UI.

### The Hybrid Approach

This is the architecture that makes sense. Java native addon handles the heavy lifting (reading chunk data from disk, computing heatmaps, creating marker overlays). Companion JS web addon handles the interactive bits (click-to-select chunks, generate commands, export). One deployable JAR, no server mod needed.

---

## The Marker API — Exactly What You Need

BlueMapAPI v2.7.7 gives you these marker types:

| Type | What It Does | Relevance |
|------|-------------|-----------|
| **ShapeMarker** | Flat 2D coloured polygon on the xz-plane | **This is the one.** Chunk overlay squares. |
| ExtrudeMarker | 3D extruded shape with height | Could show chunks as 3D columns (probably overkill) |
| HtmlMarker | Arbitrary HTML placed on the map | Interactive UI elements, info popups |
| LineMarker | Coloured lines on the map | Chunk borders (but BlueMap already has these built in) |
| POIMarker | Point-of-interest icon | Not really relevant |

Creating a coloured chunk overlay is straightforward:

```java
Shape chunkShape = Shape.createRect(
    chunkX * 16, chunkZ * 16,
    (chunkX + 1) * 16, (chunkZ + 1) * 16
);
ShapeMarker marker = ShapeMarker.builder()
    .label("Chunk " + chunkX + ", " + chunkZ)
    .shape(chunkShape, 64)
    .fillColor(new Color(255, 0, 0, 0.3f))
    .lineColor(new Color(255, 0, 0, 1.0f))
    .lineWidth(2)
    .depthTestEnabled(false)
    .build();
```

**MarkerSets** are toggleable layers in the UI sidebar. You'd have separate layers for the heatmap overlay, the "player modified" overlay, and the selection overlay — users can toggle each independently.

**One gotcha**: markers are NOT persistent across BlueMap restarts. You need to recreate them on every load/reload. `MarkerGson` provides JSON serialisation so you can save and restore state, but you have to handle it yourself.

**You don't need to draw chunk borders** — BlueMap already has those built in, toggled via the Settings menu as shader-based grid lines.

---

## Feature 1: Player Time Heatmap

### The Data: InhabitedTime

Every chunk stores an `InhabitedTime` tag — a `TAG_Long` at the top level of the chunk's NBT data.

Key details:

- **Unit**: game ticks (20 ticks = 1 second)
- **Aggregate across all players** — not per-player tracking
- In multiplayer: 2 players in a chunk for 1 second = 40 ticks (both contribute simultaneously)
- Used by Minecraft for local difficulty scaling
- **A value of 0 means no player has ever been in that chunk**
- Conversion: 72,000 ticks = 1 hour of single-player presence

This is the same data MCA Selector uses for its InhabitedTime filter. It's reliable and well-understood.

**What about per-player data?** Per-player stats (`stats/<uuid>.json`) only store global totals, not per-chunk breakdowns. There's no way to know which player spent time where. For your use case (two players, just want to know if chunks are worth keeping) the aggregate is perfectly fine.

**Known issues**: Some MC versions (1.12.2, 1.17.1, 1.18.2) had bugs where InhabitedTime didn't update correctly. Worth checking what version you're on, but unlikely to matter unless you're on one of those specific builds.

**Important non-useful tag**: `LastUpdate` looks tempting but it's just the world tick when the chunk was last saved. Autosave resaves everything, so it tells you nothing about player activity.

---

## Feature 2: Player Block Placement Detection

### The Problem

There is **no flag anywhere in Minecraft's data format** for "this block was placed by a player." Every single tool in the ecosystem uses heuristics. This is a solved problem, just not a clean one.

### The Solution: Combined Heuristics

| Signal | What It Means |
|--------|--------------|
| InhabitedTime = 0 | **Definitely unmodified.** Nobody has ever been here. Safe to trim. |
| InhabitedTime > 0, no non-natural blocks in palette, no tile entities | Likely just explored or travelled through. Probably safe. |
| InhabitedTime > 0, has non-natural blocks OR tile entities | **Possibly player-modified.** Flag for review. |
| InhabitedTime > threshold (e.g. 1+ hour) | Significant player activity. Almost certainly keep. |

### Block Palette Scanning

This is where it gets clever. Each 16x16x16 chunk section has a **palette** — a list of distinct block types present in that section. You don't need to check every single block. Just scan the palette for non-natural blocks. This is extremely fast.

**Blocks that strongly indicate player activity**: chests, crafting tables, furnaces, signs, rails, redstone components, concrete, stripped logs, beacons, scaffolding, beds (outside villages), etc.

**False positive risk from generated structures**: Villages contain beds, doors, and crafting tables. Mineshafts have torches and rails. Temples have chests, TNT, and dispensers. But here's why the combined approach works — **a village chunk that nobody's visited has InhabitedTime = 0**. The palette scan only matters for chunks where InhabitedTime > 0, which filters out the vast majority of generated-structure noise.

### Tile Entity Count

The `block_entities` list in chunk data contains blocks with extra NBT data: chests, signs, furnaces, banners, etc. A high tile entity count is a strong secondary signal of player activity. Quick to read from the chunk's top-level NBT.

---

## Reading Chunk Data From Disk

### Querz NBT Library

The **Querz NBT library** (`net.querz:nbt`) is what MCA Selector uses under the hood. Pure file I/O, no running server needed.

```java
MCAFile mca = MCAUtil.read("path/to/r.0.0.mca");
for (int x = 0; x < 32; x++) {
    for (int z = 0; z < 32; z++) {
        Chunk chunk = mca.getChunk(x, z);
        if (chunk != null) {
            long inhabitedTime = chunk.getInhabitedTime();
            // palette scanning, tile entity counting, etc.
        }
    }
}
```

Maven dependency: `net.querz:nbt:6.1` (verify latest version).

### Region File Layout

Region files live at `<world>/region/r.X.Z.mca`. Each file contains a 32x32 grid of chunks. The region coordinates are derived from chunk coordinates: `X = chunkX >> 5`, `Z = chunkZ >> 5`.

### Performance

Reading `InhabitedTime` is trivial — it's a top-level tag, no decompression of block data needed. Palette scanning requires decompressing chunk sections but you skip the packed block array entirely. **100k chunks is scannable in seconds.** For a two-player world that's been running a couple of years, this will be fast.

---

## The Chunky Problem

### What Chunky Can Do

Chunky trim syntax:

```
chunky trim [world] [shape] [centerX centerZ] [radius] [radius] [outside|inside] [inhabited]
```

Available shapes: square, circle, rectangle, ellipse, triangle, diamond, pentagon, hexagon, star.

The `inhabited` parameter is genuinely useful: `chunky trim world square 0 0 30000000 outside 0` would trim every chunk on the server with an InhabitedTime of 0.

### What Chunky Can't Do

**Only geometric shapes.** You cannot give Chunky a list of arbitrary scattered chunks and say "delete these." There's no CSV import, no coordinate list, nothing.

The public Chunky API has no trim method either — it's command-dispatch only.

### Workarounds

**1. Multiple inside-mode trims** — Decompose your selection into rectangles, chain `chunky trim ... inside` commands for each one. The addon would need to compute a minimal rectangle decomposition of the selected chunks. Clunky but workable.

**2. Custom Shape registration** — Chunky's `ShapeFactory.registerCustom()` lets a plugin register a shape whose `isBounding()` method checks coordinates against an arbitrary set. Requires building a small companion Chunky plugin. Most elegant solution but more moving parts.

**3. MCA Selector CSV export** — MCA Selector's CLI accepts a CSV file of chunk coordinates for arbitrary deletion. The Chunky maintainer themselves has recommended this for precise chunk deletion. Your BlueMap addon could generate the CSV, then you run MCA Selector CLI against it.

**4. Direct region file editing** — Delete chunks directly from .mca files programmatically (which is what MCA Selector does internally). Most powerful, most dangerous. One bug and you've corrupted your world.

### The Practical Answer

For your use case, the **MCA Selector CSV approach** is probably the sweet spot. Your BlueMap addon generates a CSV of selected chunk coordinates, you run MCA Selector CLI to do the actual deletion. Clean separation of concerns — the BlueMap addon handles visualisation and selection, a battle-tested tool handles the destructive operation.

If you want everything self-contained, the **Chunky custom shape plugin** is the next best option, but it means maintaining two separate plugins.

---

## What Already Exists (Nothing Like This)

### Searched Everywhere

GitHub, SpigotMC, Modrinth, CurseForge, community forums. **No tool provides an interactive web-map-based interface for visualising chunk activity and generating trim commands.** Not for BlueMap, not for Dynmap, not for Squaremap. This is a genuine gap across the entire Minecraft web-map ecosystem.

### Relevant BlueMap Addons (Prove the Pattern Works)

| Addon | What It Does | Why It Matters |
|-------|-------------|---------------|
| **FTB-Chunks-for-BlueMap** | Shows land claims as coloured per-chunk overlays | **Proves chunk-level ShapeMarker overlays work at scale** |
| **Bonfire** | Chunk claiming with BlueMap integration | Same pattern, confirms viability |
| **discover-border-chunks** | Python script creating BlueMap markers from chunk data | Proves the external-data-to-markers pipeline |
| **BlueBridge** | WorldGuard/GriefPrevention regions on BlueMap | Region overlay patterns |
| **Distance Measurer** | Web addon for interactive map tools | Shows how to intercept clicks and build interactive UI |

### Standalone Chunk Management Tools

| Tool | Type | Key Features |
|------|------|-------------|
| **MCA Selector** | Desktop GUI (Java) | Gold standard. 10 overlay types, 25 filter types, InhabitedTime visualisation. Exactly what you want to build, but desktop-only with no BlueMap integration. |
| **WorldPruner** (by Querz) | CLI | InhabitedTime-based pruning with radius buffer around kept chunks |
| **ChunkCleaner** | CLI (Go) | InhabitedTime-based deletion |
| **PotatoPeeler** | CLI | Most feature-rich CLI pruner (InhabitedTime, protection zones, server wrapper) |

MCA Selector is the closest thing to what you're building. The difference is you'd be doing it in-browser on top of your actual rendered map, which is dramatically more intuitive for deciding what to keep.

---

## Architecture Options

### Option A: Pure BlueMap Native Addon (Recommended)

Java JAR in `packs/` folder + companion JS web addon bundled inside it.

- Native addon reads `.mca` files via Querz NBT, computes chunk metadata, creates ShapeMarker overlays
- Web addon adds interactive UI: click-to-select, deselect, generate commands/CSV button
- Works without any server mod — even works with BlueMap CLI standalone
- One deployment artifact
- Downside: needs file path configuration to find world data (or auto-detect from BlueMap's map config)

### Option B: Server Plugin + BlueMap API

Traditional Paper/Spigot plugin that depends on BlueMapAPI.

- Can access world data through server API (but reading .mca directly is still better for performance)
- More natural integration with Chunky (can dispatch commands directly via server console)
- Downside: platform-specific (Paper vs Fabric etc.), requires server running, tied to server lifecycle

### Option C: External Script

Standalone script (Python/Java) that reads `.mca` files and generates BlueMap marker JSON.

- Simplest to build
- No live interactivity — regenerate markers after each run
- Good for features 1 and 2 (overlays), poor for feature 3 (interactive selection)
- Basically a worse version of Option A with less effort

### Output Format Options

| Approach | Flexibility | Risk | Effort |
|----------|------------|------|--------|
| MCA Selector CSV export | Arbitrary chunks, battle-tested tool does deletion | Low | Low |
| Multiple Chunky `trim inside` commands | Rectangle approximations only | Low | Low |
| Chunky custom Shape plugin | Arbitrary chunks, native integration | Low | Medium (separate plugin) |
| Direct .mca file editing | Maximum control | **High** (corruption risk) | Medium |

---

## Verdict / Recommendations

### Is this worth building?

Yes. The gap is real — nobody has built this for any web map platform. MCA Selector proves the concept works; you're just moving it into the browser on top of an actual rendered map instead of a flat grid. For a two-player world where you know your builds and just want to clean up explored wilderness, this is exactly the right tool.

The existing BlueMap addons (FTB-Chunks, Bonfire) prove that per-chunk ShapeMarker overlays work at scale. You're not pioneering a new pattern, you're applying an established one to a new problem.

### How hard would it be?

**The core is surprisingly straightforward.** The data access (Querz NBT), the visualisation (BlueMap ShapeMarker API), and the UI patterns (web addon click handling) are all well-documented with working examples to reference.

The trickiest parts, in order:

1. **Interactive chunk selection in the web addon** — intercepting map clicks, converting screen coordinates to chunk coordinates, managing selection state. The Distance Measurer addon is a good reference but you're building something more complex.

2. **Smart output generation** — decomposing arbitrary chunk selections into Chunky-compatible commands or MCA Selector CSV. The CSV route is simpler.

3. **Performance at scale** — making sure the overlay doesn't murder the browser when you've got thousands of chunks rendered. FTB-Chunks-for-BlueMap has solved this, so it's clearly possible, but worth studying how they handle it.

The block palette scanning heuristic needs some tuning (what counts as "non-natural") but you can start with a generous list and refine it.

### Recommended Approach

**Option A — Pure BlueMap Native Addon with hybrid Java + JS architecture.**

1. Ship as a single JAR in `packs/`
2. Java side: scan `.mca` files, compute InhabitedTime heatmap + block modification heuristics, create ShapeMarker overlays in toggleable MarkerSets
3. JS side: interactive chunk selection UI, command/CSV generation, export button
4. Output: MCA Selector CSV for the actual chunk deletion (let a proven tool handle the destructive bit)
5. Persist scan results and selection state to JSON (using MarkerGson or custom serialisation) so it survives BlueMap reloads

This gives you everything you want — no server mod dependency, works on any platform, clean separation between "show me what I've got" and "delete the stuff I don't want."

Start with the InhabitedTime heatmap overlay (Feature 1) as a standalone first milestone. It's the simplest, most immediately useful piece, and it proves out the entire data pipeline from `.mca` files through to rendered markers. Features 2 and 3 build on that foundation.
