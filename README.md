# BlueMap Chunk Trimmer

A BlueMap native addon that scans Minecraft `.mca` region files, visualizes chunk activity as BlueMap overlays, and provides interactive chunk selection for trimming.

## Features

- **InhabitedTime heatmap overlay** — color-coded chunks showing player activity
- **Interactive chunk selection** — click to select/deselect, Ctrl/Cmd+drag to paint, Shift+drag to rectangle-select
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

### CSV (MCA Selector format)

Semicolon-delimited, no header. Can be used directly with MCA Selector's chunk filter.

```csv
0;0;-9;2
0;0;-9;3
```

### Field reference

**JSON fields:**

| Field | Description |
|-------|-------------|
| `chunkX` | Chunk X coordinate. Each chunk is a 16x16 block column, so chunk X of -9 covers blocks -144 to -129. Convert with `chunkX = floor(blockX / 16)`. |
| `chunkZ` | Chunk Z coordinate (same convention as chunkX, on the Z axis). |
| `inhabitedTime` | Cumulative time (in game ticks, 20 ticks = 1 second) that players have spent in this chunk. Higher values indicate more player activity. |

**CSV columns** (semicolon-delimited, in order):

| Column | Description |
|--------|-------------|
| `regionX` | Region file X coordinate (`floor(chunkX / 32)`). |
| `regionZ` | Region file Z coordinate. |
| `chunkX` | Chunk X coordinate. |
| `chunkZ` | Chunk Z coordinate. |

## Build

```bash
./gradlew shadowJar  # outputs to build/libs/bluemap-chunk-trimmer-0.1.0.jar
```

## Install

Place the JAR in BlueMap's `packs/` directory (not `mods/`). Requires Java 21.
