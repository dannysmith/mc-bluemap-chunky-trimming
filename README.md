# BlueMap Chunk Trimmer

A BlueMap native addon that scans Minecraft `.mca` region files, visualizes chunk activity as BlueMap overlays, and provides interactive chunk selection for trimming.

## Features

- **InhabitedTime heatmap overlay** — color-coded chunks showing player activity
- **Interactive chunk selection** — Ctrl/Cmd+click chunks on the map to select/deselect
- **Export selections** — download selected chunks as JSON or CSV for use with trimming tools

## Export Format

The chunk selector exports JSON and CSV files containing the selected chunks.

### JSON

```json
{
  "chunks": [
    {
      "chunkX": -9,
      "chunkZ": 2,
      "inhabitedTime": 161
    }
  ]
}
```

### CSV

```csv
chunkX,chunkZ
-9,2
-9,3
```

### Field reference

| Field | Description |
|-------|-------------|
| `chunkX` | Chunk X coordinate. Each chunk is a 16x16 block column, so chunk X of -9 covers blocks -144 to -129. Convert with `chunkX = floor(blockX / 16)`. This is the standard Minecraft chunk coordinate system used by region files, the F3 debug screen, and tools like MCA Selector. |
| `chunkZ` | Chunk Z coordinate (same convention as chunkX, on the Z axis). |
| `inhabitedTime` | Cumulative time (in game ticks, 20 ticks = 1 second) that players have spent in this chunk. Higher values indicate more player activity. |

## Build

```bash
./gradlew shadowJar  # outputs to build/libs/bluemap-chunk-trimmer-0.1.0.jar
```

## Install

Place the JAR in BlueMap's `packs/` directory (not `mods/`). Requires Java 21.
