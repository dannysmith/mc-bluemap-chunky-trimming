# BlueMap Chunk Trimmer

A [BlueMap](https://bluemap.bluecolored.de/) addon that helps you visualize and manage chunk activity on your Minecraft server. It reads the `InhabitedTime` NBT data from your `.mca` region files and renders it as a color-coded heatmap overlay directly on your BlueMap, so you can see at a glance where players have actually spent time. It also includes an interactive chunk selector that lets you pick chunks visually and export them for deletion with tools like MCA Selector — useful for trimming unused chunks to reduce world size or force terrain regeneration.

Both features are only available in BlueMap's flat (2D) view.

> **Heatmap data is read once at startup.** The addon parses region files when BlueMap first loads, so the heatmap reflects the state of the world at that point. To pick up new activity, restart the server or run `/bluemap reload`.

## Heatmap Overlay

<img width="1920" height="957" alt="bluemap-playertijme-heatmap" src="https://github.com/user-attachments/assets/1728f2d4-257b-4c23-93cb-87cb5f111042" />

The heatmap shows cumulative player activity per chunk. Each chunk that's been inhabited for more than 1 minute gets a coloured overlay, with the colour indicating how much total time players have spent there. Chunks are coloured by tier:

- **Faint blue** — 1–5 minutes
- **Blue** — 5–10 minutes
- **Brighter blue** — 10–30 minutes
- **Teal/cyan** — 30 minutes – 1 hour
- **Yellow/amber** — 1–3 hours
- **Orange** — 3–10 hours
- **Deep red** — 10+ hours

Chunks with less than 1 minute of inhabited time don't appear coloured on the heatmap at all. When the heatmap is visible, a small HUD appears at the bottom of the screen as you move your cursor: it shows the chunk coordinates and inhabited time for all chunks which have non-zero inhabited-time.

You can toggle the heatmap on and off using the thermometer icon in the top-left control bar, so long as you're in _flat_ mode.

## Chunk Selector

<img width="1210" height="805" alt="bluemap-chunk-selector" src="https://github.com/user-attachments/assets/b794cd9b-986e-439c-aa01-8d41db1ab3f1" />

The chunk selector lets you visually select chunks on the map for export as JSON or CSV data. You can enable it by clicking the chunk trimmer icon in the top-left toolbar (also only visible in flat view mode).

### Selecting chunks

- **Ctrl/Cmd + click** a chunk to select or deselect it. Selected chunks appear with a bright hatched pattern.
- **Ctrl/Cmd + click and drag** to paint across multiple chunks. Whether this selects or deselects depends on the state of the first chunk you click — if it's unselected, dragging selects; if it's already selected, dragging deselects.
- **Shift + click and drag** to draw a rectangle. All chunks inside the rectangle get selected (or deselected, same logic as above).

Selections are per-dimension and persist in your browser's local storage, so they survive page refreshes and map changes.

### Exporting selections

The panel in the bottom-right shows your current selection count and provides two export options:

#### JSON Export

Intended primarily as a somewhat standard format which can be transformed by other tooling into a useful input for chunk-trimming tools. Includes basic world metadata and for each selected chunk: coordinates & inhabited time data (as game ticks)...

```json
{
  "dimension": "overworld",
  "worldName": "world",
  "chunks": [
    { "chunkX": -9, "chunkZ": 2, "inhabitedTime": 161 },
    { "chunkX": 10, "chunkZ": 15, "inhabitedTime": 45000 }
  ]
}
```

#### CSV Export

Exports selected chunks in MCA Selector's semicolon-delimited format, which you can use directly with MCA Selector's chunk filter to delete the selected chunks. The four columns are `regionX;regionZ;chunkX;chunkZ` (with no header row). **As with all chunk trimming: back up your world data and double-check the chunk data you're giving to the trimmer!**.

```csv
0;0;-9;2
0;0;10;15
```

## Installation

Place the JAR in BlueMap's `packs/` directory. This is a BlueMap native addon not a Fabric/Forge mod. Requires Java 21.

## Development

Requires Java 21 and Gradle (wrapper included).

```bash
./gradlew shadowJar    # build fat JAR → build/libs/bluemap-chunk-trimmer-0.1.0.jar
./gradlew compileJava  # compile check only
```

The shadow JAR relocates the [Querz NBT](https://github.com/Querz/NBT) library to avoid classpath conflicts. BlueMap provides Gson at runtime, so it's not bundled.
