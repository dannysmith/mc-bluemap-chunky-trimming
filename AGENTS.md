# AI Instructions - BlueMap Chunk Trimmer

BlueMap addon (Java JAR) that scans Minecraft `.mca` region files, visualizes chunk activity as BlueMap overlays, and provides interactive chunk selection for trimming. See `plan.md` for architecture and progress, `requirements.md` for the original brief, `research.md` for API findings.

## Project structure

- `src/main/java/dev/danny/chunktrimmer/` — Java source (entrypoint: `ChunkTrimmerAddon.java`)
  - `scanner/` — Region file reading, chunk analysis, caching
  - `overlay/` — BlueMap marker overlay generation (heatmap, modified, selection)
  - `web/` — Data export to web root + web addon installer
- `src/main/resources/bluemap.addon.json` — BlueMap addon descriptor
- `src/main/resources/web/` — Web addon JS/CSS (copied to web root at runtime)
- `build.gradle` — Gradle build with shadow JAR (relocates Querz NBT)

## Build

```bash
./gradlew shadowJar    # builds to build/libs/bluemap-chunk-trimmer-0.1.0.jar
./gradlew compileJava  # compile check only
```

## Deploy to server (server-side only)

**This section only applies when working directly on the server** (where `/opt/minecraft/` exists). When developing locally, build with `./gradlew shadowJar` and push to GitHub — deployment happens after pulling on the server.

This is a **BlueMap native addon**, not a Fabric mod. It goes in BlueMap's `packs/` directory, not the Fabric `mods/` directory.

```bash
# Build and deploy to bmdev (default server unless told otherwise)
./gradlew shadowJar
cp build/libs/bluemap-chunk-trimmer-0.1.0.jar /opt/minecraft/servers/bmdev/data/config/bluemap/packs/

# Restart to load
mc-stop bmdev && mc-start bmdev
```

Do NOT place the JAR in `data/mods/` — Fabric will ignore it (no `fabric.mod.json`) and BlueMap won't find it there.

## Key dependencies

- `de.bluecolored:bluemap-api:2.7.7` (compileOnly) — Java 21 required
- `com.github.Querz:NBT:6.1` — region file / NBT parsing
- `com.google.code.gson:gson:2.10.1` (compileOnly, provided by BlueMap at runtime)
