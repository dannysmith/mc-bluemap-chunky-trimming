# AI Instructions - BlueMap Chunk Trimmer

BlueMap addon (Java JAR) that scans Minecraft `.mca` region files, visualizes chunk activity as BlueMap overlays, and provides interactive chunk selection for trimming. See `plan.md` for architecture and progress, `requirements.md` for the original brief, `research.md` for API findings.

## Project structure

- `src/main/java/dev/danny/chunktrimmer/` — Java source (entrypoint: `ChunkTrimmerAddon.java`)
  - `scanner/` — Region file reading, chunk analysis, caching
  - `overlay/` — BlueMap marker overlay generation (heatmap, modified, selection)
  - `web/` — Data export to BlueMap AssetStorage for the web addon
- `src/main/resources/bluemap.addon.json` — BlueMap addon descriptor
- `build.gradle` — Gradle build with shadow JAR (relocates Querz NBT)

## Build

```bash
./gradlew shadowJar    # builds to build/libs/bluemap-chunk-trimmer-0.1.0.jar
./gradlew compileJava  # compile check only
```

## Key dependencies

- `de.bluecolored:bluemap-api:2.7.7` (compileOnly) — Java 21 required
- `com.github.Querz:NBT:6.1` — region file / NBT parsing
- `com.google.code.gson:gson:2.10.1` (compileOnly, provided by BlueMap at runtime)
