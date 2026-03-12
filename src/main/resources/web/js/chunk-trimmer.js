/**
 * BlueMap Chunk Trimmer — Web Addon
 *
 * Adds inhabited time heatmap and interactive chunk selection to BlueMap:
 * - Heatmap overlay rendered client-side via InstancedMesh (toggle in control bar)
 * - Chunk selector toggle button in control bar
 * - Ctrl/Cmd+click to select/deselect chunks
 * - Selection panel with count, clear, export
 * - Three.js overlay meshes for selected chunks
 */
(function () {
    "use strict";

    const THREE = BlueMap.Three;
    const app = bluemap;

    // ── State ──────────────────────────────────────────────────

    let active = false;
    let scanData = null;

    // Selection: per-world sets of "x,z" strings
    let selections = {};       // worldId -> Set<string>
    let selection = new Set();  // alias → active world's set
    let currentWorldId = null;
    let lastMapId = null;
    let heatmapVisible = false; // driven by toggle button
    let inFlatView = true;

    // Three.js objects for selection overlay
    const selectionMeshes = new Map(); // "x,z" -> Mesh
    let selectionGroup = null;
    let overlayEl = null;
    let hudEl = null;
    let lastHoverKey = null;

    // Heatmap
    let heatmapGroup = null;    // THREE.Group containing InstancedMesh objects
    let heatmapBtn = null;      // toggle button element

    // ── Constants ──────────────────────────────────────────────

    const CHUNK_SIZE = 16;
    const OVERLAY_Y = 65;
    const SELECTION_COLOR = 0xff3399; // bright pink — distinct from heatmap blue/yellow/red
    const STORAGE_KEY = "chunk-trimmer-selection";
    const DATA_PATH = "assets/chunk-trimmer/data.json";

    // Heatmap color buckets — matches Java HeatmapColors thresholds.
    // Each chunk is assigned to the first bucket where inhabitedTime < max.
    // Using discrete buckets with one InstancedMesh per bucket gives us
    // 7 draw calls instead of 36k+ individual ShapeMarkers.
    const HEATMAP_MIN_TICKS = 1200; // 1 minute — below this, chunks are invisible
    const HEATMAP_BUCKETS = [
        { max: 6000,     r: 77,  g: 128, b: 230, a: 0.12 },  // 1-5m: faint blue
        { max: 12000,    r: 77,  g: 128, b: 230, a: 0.18 },  // 5-10m: blue
        { max: 36000,    r: 77,  g: 128, b: 230, a: 0.25 },  // 10-30m: brighter blue
        { max: 72000,    r: 51,  g: 204, b: 204, a: 0.33 },  // 30m-1h: teal
        { max: 216000,   r: 230, g: 204, b: 51,  a: 0.42 },  // 1-3h: yellow/amber
        { max: 720000,   r: 230, g: 120, b: 26,  a: 0.52 },  // 3-10h: orange
        { max: Infinity, r: 204, g: 30,  b: 20,  a: 0.60 },  // 10h+: deep red
    ];

    // Shared geometry for all selection meshes
    const chunkGeometry = new THREE.PlaneGeometry(CHUNK_SIZE, CHUNK_SIZE);
    chunkGeometry.rotateX(-Math.PI / 2); // lay flat on xz-plane

    // Pre-allocated Three.js objects for raycasting (reused every mousemove)
    const _mouseVec = new THREE.Vector2();
    const _raycaster = new THREE.Raycaster();
    const _intersection = new THREE.Vector3();

    // Diagonal stripe texture for selected chunks
    var stripeTexture = createStripeTexture();
    const selectionMaterial = new THREE.MeshBasicMaterial({
        map: stripeTexture,
        transparent: true,
        side: THREE.DoubleSide,
        depthTest: false,
    });

    function createStripeTexture() {
        var size = 64;
        var canvas = document.createElement("canvas");
        canvas.width = size;
        canvas.height = size;
        var ctx = canvas.getContext("2d");

        ctx.clearRect(0, 0, size, size);

        // Diagonal stripes in bright pink
        ctx.strokeStyle = "rgba(255, 51, 153, 0.55)";
        ctx.lineWidth = 7;
        for (var i = -size; i < size * 2; i += 16) {
            ctx.beginPath();
            ctx.moveTo(i, size);
            ctx.lineTo(i + size, 0);
            ctx.stroke();
        }

        var texture = new THREE.CanvasTexture(canvas);
        texture.wrapS = THREE.RepeatWrapping;
        texture.wrapT = THREE.RepeatWrapping;
        return texture;
    }

    // Border material
    const borderGeometry = new THREE.EdgesGeometry(chunkGeometry);
    const borderMaterial = new THREE.LineBasicMaterial({
        color: SELECTION_COLOR,
        linewidth: 2,
        depthTest: false,
    });

    // ── Initialization ─────────────────────────────────────────

    function init() {
        createUI();
        createOverlay();
        createHud();
        loadSelection();

        // Set initial world (may use map ID as fallback before scan data loads)
        var wid = getCurrentWorldId();
        if (wid) {
            currentWorldId = wid;
            if (!selections[wid]) selections[wid] = new Set();
            selection = selections[wid];
        }

        // Hide UI immediately if not in flat mode
        inFlatView = isFlatView();
        if (!inFlatView) {
            if (toggleBtn) toggleBtn.style.display = "none";
            if (heatmapBtn) heatmapBtn.style.display = "none";
            if (spacerEl) spacerEl.style.display = "none";
        }

        fetchScanData();
    }

    // ── Scan Data ──────────────────────────────────────────────

    function fetchScanData() {
        fetch(DATA_PATH)
            .then((r) => {
                if (!r.ok) throw new Error(r.status);
                return r.json();
            })
            .then((data) => {
                // Flatten all world chunks into a single lookup map
                // for export enrichment (keyed by "x,z")
                const allChunks = {};
                const worlds = data.worlds || {};
                const mapToWorld = {};
                let totalChunks = 0;
                for (const worldId of Object.keys(worlds)) {
                    const world = worlds[worldId];
                    const worldChunks = world.chunks || {};
                    Object.assign(allChunks, worldChunks);
                    totalChunks += Object.keys(worldChunks).length;
                    // Build map ID → world ID index for world-aware lookups
                    if (world.mapIds) {
                        for (var i = 0; i < world.mapIds.length; i++) {
                            mapToWorld[world.mapIds[i]] = worldId;
                        }
                    }
                }
                scanData = { worlds: worlds, chunks: allChunks, mapToWorld: mapToWorld };
                console.log(
                    "[ChunkTrimmer] Loaded scan data: " +
                        totalChunks + " chunks across " +
                        Object.keys(worlds).length + " world(s)"
                );

                // Resolve world and start tracking map switches
                migrateSelectionKeys();
                var wid = getCurrentWorldId();
                if (wid) switchToWorld(wid);
                startMapPolling();

                // Build heatmap for initial world
                if (currentWorldId) buildHeatmap(currentWorldId);
            })
            .catch(() => {
                console.log("[ChunkTrimmer] No scan data available yet");
            });
    }

    // ── World Tracking ────────────────────────────────────────

    function getCurrentWorldId() {
        try {
            var mapId = app.mapViewer.map.data.id;
            if (scanData && scanData.mapToWorld[mapId]) {
                return scanData.mapToWorld[mapId];
            }
            return mapId;
        } catch (e) {
            return null;
        }
    }

    function getDimensionLabel(worldId) {
        var labels = {
            "overworld": "Overworld",
            "the_nether": "Nether",
            "the_end": "The End"
        };
        return labels[worldId] || worldId;
    }

    function switchToWorld(worldId) {
        if (worldId === currentWorldId) return;
        currentWorldId = worldId;
        if (!selections[currentWorldId]) selections[currentWorldId] = new Set();
        selection = selections[currentWorldId];
        if (active) {
            rebuildAllMeshes();
        }
        updatePanel();
        buildHeatmap(worldId);
    }

    /**
     * Migrates selection keys that are map IDs to their proper world IDs.
     * Called after scan data loads so mapToWorld is available.
     */
    function migrateSelectionKeys() {
        if (!scanData) return;
        var changed = false;
        for (var key of Object.keys(selections)) {
            var worldId = scanData.mapToWorld[key];
            if (worldId && worldId !== key) {
                if (!selections[worldId]) selections[worldId] = new Set();
                for (var chunk of selections[key]) {
                    selections[worldId].add(chunk);
                }
                delete selections[key];
                changed = true;
            }
        }
        if (changed) saveSelection();
    }

    function startMapPolling() {
        setInterval(function () {
            var wid = getCurrentWorldId();
            if (wid && wid !== currentWorldId) {
                switchToWorld(wid);
                saveSelection();
            }
            var flat = isFlatView();
            if (flat !== inFlatView) onViewModeChanged(flat);
        }, 500);
    }

    // ── Flat View Gating ─────────────────────────────────────

    function isFlatView() {
        try {
            var hash = window.location.hash;
            if (hash) {
                var parts = hash.substring(1).split(":");
                return parts[parts.length - 1] === "flat";
            }
        } catch (e) {}
        return true;
    }

    function onViewModeChanged(flat) {
        inFlatView = flat;
        if (flat) {
            if (toggleBtn) toggleBtn.style.display = "";
            if (heatmapBtn) heatmapBtn.style.display = "";
            if (spacerEl) spacerEl.style.display = "";
            if (active && panelEl) panelEl.style.display = "block";
            if (active) rebuildAllMeshes();
            if (heatmapGroup) heatmapGroup.visible = heatmapVisible;
        } else {
            if (toggleBtn) toggleBtn.style.display = "none";
            if (heatmapBtn) heatmapBtn.style.display = "none";
            if (spacerEl) spacerEl.style.display = "none";
            if (panelEl) panelEl.style.display = "none";
            if (overlayEl) overlayEl.style.pointerEvents = "none";
            clearSelectionMeshes();
            if (heatmapGroup) heatmapGroup.visible = false;
            hideHud();
        }
    }

    // ── Heatmap Rendering ─────────────────────────────────────

    /**
     * Builds the heatmap overlay for a world using InstancedMesh.
     * Chunks are sorted into color buckets, one InstancedMesh per bucket.
     * This gives us ~7 draw calls instead of tens of thousands of individual markers.
     */
    function buildHeatmap(worldId) {
        destroyHeatmap();

        if (!scanData || !scanData.worlds[worldId]) return;

        var chunks = scanData.worlds[worldId].chunks;
        if (!chunks) return;

        // Sort chunks into buckets
        var bucketKeys = HEATMAP_BUCKETS.map(function () { return []; });

        var keys = Object.keys(chunks);
        for (var k = 0; k < keys.length; k++) {
            var it = chunks[keys[k]].it;
            if (it < HEATMAP_MIN_TICKS) continue;

            for (var b = 0; b < HEATMAP_BUCKETS.length; b++) {
                if (it < HEATMAP_BUCKETS[b].max) {
                    bucketKeys[b].push(keys[k]);
                    break;
                }
            }
        }

        heatmapGroup = new THREE.Group();

        var dummy = new THREE.Object3D();
        var totalChunks = 0;

        for (var b = 0; b < HEATMAP_BUCKETS.length; b++) {
            var bKeys = bucketKeys[b];
            if (bKeys.length === 0) continue;

            var bucket = HEATMAP_BUCKETS[b];
            var material = new THREE.MeshBasicMaterial({
                color: new THREE.Color(bucket.r / 255, bucket.g / 255, bucket.b / 255),
                transparent: true,
                opacity: bucket.a,
                depthTest: false,
                side: THREE.DoubleSide,
            });

            var mesh = new THREE.InstancedMesh(chunkGeometry, material, bKeys.length);

            for (var i = 0; i < bKeys.length; i++) {
                var parts = bKeys[i].split(",");
                var cx = parseInt(parts[0]);
                var cz = parseInt(parts[1]);
                dummy.position.set(
                    cx * CHUNK_SIZE + CHUNK_SIZE / 2,
                    OVERLAY_Y,
                    cz * CHUNK_SIZE + CHUNK_SIZE / 2
                );
                dummy.updateMatrix();
                mesh.setMatrixAt(i, dummy.matrix);
            }

            mesh.instanceMatrix.needsUpdate = true;
            mesh.frustumCulled = false;
            mesh.raycast = function () {}; // prevent interference with BlueMap raycasting
            heatmapGroup.add(mesh);
            totalChunks += bKeys.length;
        }

        heatmapGroup.visible = heatmapVisible && inFlatView;
        app.mapViewer.markers.add(heatmapGroup);

        console.log("[ChunkTrimmer] Built heatmap: " + totalChunks + " chunks in " +
            heatmapGroup.children.length + " draw calls");
    }

    function destroyHeatmap() {
        if (heatmapGroup) {
            app.mapViewer.markers.remove(heatmapGroup);
            // Dispose materials (geometry is shared, don't dispose it)
            for (var i = 0; i < heatmapGroup.children.length; i++) {
                var child = heatmapGroup.children[i];
                if (child.material) child.material.dispose();
                if (child.instanceMatrix) child.instanceMatrix = null;
            }
            heatmapGroup = null;
        }
    }

    function toggleHeatmap() {
        heatmapVisible = !heatmapVisible;
        if (heatmapBtn) heatmapBtn.classList.toggle("active", heatmapVisible);
        if (heatmapGroup) heatmapGroup.visible = heatmapVisible && inFlatView;
        if (!heatmapVisible) hideHud();
    }

    // ── Click Handling ─────────────────────────────────────────

    // Ground plane for raycasting (Y = OVERLAY_Y, normal pointing up)
    const groundPlane = new THREE.Plane(new THREE.Vector3(0, 1, 0), -OVERLAY_Y);

    /**
     * Creates a transparent DOM overlay on top of the canvas.
     * When selection mode is active and Ctrl/Cmd is held, the overlay
     * captures pointer events before they reach BlueMap's canvas,
     * giving us control over chunk selection click handling.
     */
    function createOverlay() {
        const canvas = app.mapViewer.renderer.domElement;
        const parent = canvas.parentElement;

        overlayEl = document.createElement("div");
        overlayEl.id = "ct-click-overlay";

        // Ensure parent is a positioning context
        if (getComputedStyle(parent).position === "static") {
            parent.style.position = "relative";
        }

        overlayEl.style.cssText =
            "position:absolute;top:0;left:0;width:100%;height:100%;" +
            "z-index:10;pointer-events:none;cursor:crosshair;";
        parent.appendChild(overlayEl);

        // ── Drag state machine ──
        let downPos = null;       // {x, y} screen coords at pointerdown
        let dragMode = null;      // null | "paint" | "rect"
        let paintAction = null;   // "select" | "deselect"
        let paintedChunks = null; // Set of keys touched during this paint drag
        let rectAnchor = null;    // {chunkX, chunkZ} for rectangle start corner
        let rectPreview = null;   // THREE.Group for rectangle preview outline

        function chunkFromEvent(e) {
            const pos = worldPosFromMouse(e);
            if (!pos) return null;
            const chunkX = Math.floor(pos.x / CHUNK_SIZE);
            const chunkZ = Math.floor(pos.z / CHUNK_SIZE);
            return { chunkX: chunkX, chunkZ: chunkZ, key: chunkX + "," + chunkZ };
        }

        function applyPaint(chunk) {
            if (!chunk || paintedChunks.has(chunk.key)) return;
            paintedChunks.add(chunk.key);
            if (paintAction === "select") {
                selectChunk(chunk.key, chunk.chunkX, chunk.chunkZ);
            } else {
                deselectChunk(chunk.key);
            }
            updatePanel();
        }

        function resetDragState() {
            downPos = null;
            dragMode = null;
            paintAction = null;
            paintedChunks = null;
            rectAnchor = null;
            removeRectPreview();
        }

        // ── Rectangle preview ──

        function removeRectPreview() {
            if (rectPreview) {
                if (selectionGroup) selectionGroup.remove(rectPreview);
                rectPreview = null;
            }
        }

        function updateRectPreview(ax, az, bx, bz) {
            removeRectPreview();
            ensureSelectionGroup();

            var minX = Math.min(ax, bx);
            var maxX = Math.max(ax, bx);
            var minZ = Math.min(az, bz);
            var maxZ = Math.max(az, bz);

            // World coords: from min chunk's left edge to max chunk's right edge
            var x1 = minX * CHUNK_SIZE;
            var z1 = minZ * CHUNK_SIZE;
            var x2 = (maxX + 1) * CHUNK_SIZE;
            var z2 = (maxZ + 1) * CHUNK_SIZE;

            var vertices = new Float32Array([
                x1, OVERLAY_Y + 0.5, z1,
                x2, OVERLAY_Y + 0.5, z1,
                x2, OVERLAY_Y + 0.5, z1,
                x2, OVERLAY_Y + 0.5, z2,
                x2, OVERLAY_Y + 0.5, z2,
                x1, OVERLAY_Y + 0.5, z2,
                x1, OVERLAY_Y + 0.5, z2,
                x1, OVERLAY_Y + 0.5, z1,
            ]);

            var geometry = new THREE.BufferGeometry();
            geometry.setAttribute("position", new THREE.BufferAttribute(vertices, 3));

            var material = new THREE.LineBasicMaterial({
                color: 0xffffff,
                linewidth: 2,
                depthTest: false,
            });

            rectPreview = new THREE.LineSegments(geometry, material);
            selectionGroup.add(rectPreview);
        }

        function applyRectangle(ax, az, bx, bz) {
            var minX = Math.min(ax, bx);
            var maxX = Math.max(ax, bx);
            var minZ = Math.min(az, bz);
            var maxZ = Math.max(az, bz);

            for (var x = minX; x <= maxX; x++) {
                for (var z = minZ; z <= maxZ; z++) {
                    var key = x + "," + z;
                    if (paintAction === "select") {
                        selectChunk(key, x, z);
                    } else {
                        deselectChunk(key);
                    }
                }
            }
            updatePanel();
            saveSelection();
        }

        // ── Pointer events ──

        overlayEl.addEventListener("pointerdown", (e) => {
            if (e.button !== 0) return;
            e.preventDefault();
            overlayEl.setPointerCapture(e.pointerId);

            var chunk = chunkFromEvent(e);

            if (e.shiftKey) {
                // Rectangle mode
                if (!chunk) return;
                dragMode = "rect";
                paintAction = selection.has(chunk.key) ? "deselect" : "select";
                rectAnchor = { chunkX: chunk.chunkX, chunkZ: chunk.chunkZ };
                return;
            }

            // Cmd/Ctrl: record down position, wait for movement to decide paint vs click
            downPos = { x: e.clientX, y: e.clientY, chunk: chunk };
        });

        overlayEl.addEventListener("pointermove", (e) => {
            if (dragMode === null && downPos) {
                // Check if we've moved enough to enter paint mode
                var dx = e.clientX - downPos.x;
                var dy = e.clientY - downPos.y;
                if (Math.sqrt(dx * dx + dy * dy) > 5) {
                    dragMode = "paint";
                    paintedChunks = new Set();
                    // Determine action from the chunk under the original down position
                    if (downPos.chunk) {
                        paintAction = selection.has(downPos.chunk.key) ? "deselect" : "select";
                        applyPaint(downPos.chunk);
                    } else {
                        paintAction = "select";
                    }
                }
            }

            if (dragMode === "paint") {
                applyPaint(chunkFromEvent(e));
            } else if (dragMode === "rect" && rectAnchor) {
                var chunk = chunkFromEvent(e);
                if (chunk) {
                    updateRectPreview(rectAnchor.chunkX, rectAnchor.chunkZ, chunk.chunkX, chunk.chunkZ);
                }
            }
        });

        overlayEl.addEventListener("pointerup", (e) => {
            if (e.button !== 0) return;
            e.preventDefault();

            if (dragMode === "paint") {
                // Paint drag finished — save the batch
                saveSelection();
            } else if (dragMode === "rect" && rectAnchor) {
                // Rectangle drag finished — apply to all chunks in the box
                var chunk = chunkFromEvent(e);
                if (chunk) {
                    applyRectangle(rectAnchor.chunkX, rectAnchor.chunkZ, chunk.chunkX, chunk.chunkZ);
                }
                removeRectPreview();
            } else if (downPos) {
                // No drag — single click toggle
                var chunk = chunkFromEvent(e);
                if (chunk) {
                    toggleChunk(chunk.key, chunk.chunkX, chunk.chunkZ);
                }
            }

            resetDragState();
        });

        // Cancel drag if pointer capture lost (e.g. focus change)
        overlayEl.addEventListener("lostpointercapture", () => {
            resetDragState();
        });

        // ── Modifier key tracking ──
        // Overlay intercepts pointer events when Ctrl/Cmd or Shift is held

        function isSelectionModifier(key) {
            return key === "Control" || key === "Meta" || key === "Shift";
        }

        document.addEventListener("keydown", (e) => {
            if (!active || !overlayEl) return;
            if (isSelectionModifier(e.key)) {
                overlayEl.style.pointerEvents = "auto";
            }
        });

        document.addEventListener("keyup", (e) => {
            if (!overlayEl) return;
            if (isSelectionModifier(e.key)) {
                // Only disable if no other selection modifier is still held
                if (!e.ctrlKey && !e.metaKey && !e.shiftKey) {
                    overlayEl.style.pointerEvents = "none";
                    resetDragState();
                }
            }
        });

        // Safety: reset if window loses focus while modifier held
        window.addEventListener("blur", () => {
            if (overlayEl) overlayEl.style.pointerEvents = "none";
            resetDragState();
        });
    }

    function worldPosFromMouse(e) {
        const canvas = app.mapViewer.renderer.domElement;
        const rect = canvas.getBoundingClientRect();
        _mouseVec.set(
            ((e.clientX - rect.left) / rect.width) * 2 - 1,
            -((e.clientY - rect.top) / rect.height) * 2 + 1
        );
        _raycaster.setFromCamera(_mouseVec, app.mapViewer.camera);
        return _raycaster.ray.intersectPlane(groundPlane, _intersection)
            ? _intersection
            : null;
    }

    // ── Inhabited Time HUD ────────────────────────────────────

    function createHud() {
        hudEl = document.createElement("div");
        hudEl.className = "ct-hud";
        document.body.appendChild(hudEl);

        document.addEventListener("mousemove", onHoverMove);
    }

    /**
     * Looks up chunk data for the currently viewed map's world.
     * Falls back to flattened cross-world data if the map can't be resolved.
     */
    function getChunkData(key) {
        if (!scanData) return null;
        try {
            var mapId = app.mapViewer.map.data.id;
            var worldId = scanData.mapToWorld[mapId];
            if (worldId && scanData.worlds[worldId]) {
                return scanData.worlds[worldId].chunks[key] || null;
            }
        } catch (e) {}
        return scanData.chunks[key] || null;
    }

    function onHoverMove(e) {
        if (!scanData || !hudEl || !heatmapVisible || !inFlatView) {
            hideHud();
            return;
        }

        // Check if mouse is over the canvas
        const canvas = app.mapViewer.renderer.domElement;
        const rect = canvas.getBoundingClientRect();
        if (e.clientX < rect.left || e.clientX > rect.right ||
            e.clientY < rect.top || e.clientY > rect.bottom) {
            hideHud();
            return;
        }

        const pos = worldPosFromMouse(e);
        if (!pos) {
            hideHud();
            return;
        }

        const chunkX = Math.floor(pos.x / CHUNK_SIZE);
        const chunkZ = Math.floor(pos.z / CHUNK_SIZE);
        const key = chunkX + "," + chunkZ;

        if (key === lastHoverKey) return;
        lastHoverKey = key;

        const chunk = getChunkData(key);
        if (!chunk || chunk.it < 20) {
            hideHud();
            return;
        }

        hudEl.innerHTML =
            '<div class="ct-hud-label">Chunk ' + chunkX + ', ' + chunkZ + '</div>' +
            '<div class="ct-hud-value">' + formatInhabitedTime(chunk.it) + '</div>' +
            '<div class="ct-hud-ticks">' + chunk.it.toLocaleString() + ' ticks</div>';
        hudEl.style.display = "block";
    }

    function hideHud() {
        if (hudEl) hudEl.style.display = "none";
        lastHoverKey = null;
    }

    function formatInhabitedTime(ticks) {
        var totalSeconds = Math.floor(ticks / 20);
        if (totalSeconds < 60) {
            return totalSeconds + "s";
        }
        var minutes = Math.floor(totalSeconds / 60);
        var seconds = totalSeconds % 60;
        if (minutes < 60) {
            return minutes + "m " + seconds + "s";
        }
        var hours = Math.floor(minutes / 60);
        var remainMins = minutes % 60;
        return hours + "h " + remainMins + "m";
    }

    // ── Chunk Selection ─────────────────────────────────────────

    function toggleChunk(key, chunkX, chunkZ) {
        if (selection.has(key)) {
            selection.delete(key);
            removeSelectionMesh(key);
        } else {
            selection.add(key);
            addSelectionMesh(key, chunkX, chunkZ);
        }
        updatePanel();
        saveSelection();
    }

    /** Select a chunk (no-op if already selected). Does NOT save — caller batches saves. */
    function selectChunk(key, chunkX, chunkZ) {
        if (selection.has(key)) return;
        selection.add(key);
        addSelectionMesh(key, chunkX, chunkZ);
    }

    /** Deselect a chunk (no-op if not selected). Does NOT save — caller batches saves. */
    function deselectChunk(key) {
        if (!selection.has(key)) return;
        selection.delete(key);
        removeSelectionMesh(key);
    }

    // ── Selection Meshes (Three.js) ────────────────────────────

    function ensureSelectionGroup() {
        if (selectionGroup) return;
        // Use a plain THREE.Group — not a BlueMap.MarkerSet.
        // MarkerSets get tracked and wiped by BlueMap's periodic marker refresh.
        // A plain Group added to the scene is invisible to the reconciliation logic.
        selectionGroup = new THREE.Group();
        app.mapViewer.markers.add(selectionGroup);
    }

    function addSelectionMesh(key, chunkX, chunkZ) {
        ensureSelectionGroup();

        const group = new THREE.Group();

        // Fill
        const fill = new THREE.Mesh(chunkGeometry, selectionMaterial);
        group.add(fill);

        // Border
        const border = new THREE.LineSegments(borderGeometry, borderMaterial);
        group.add(border);

        // Position at chunk center
        const cx = chunkX * CHUNK_SIZE + CHUNK_SIZE / 2;
        const cz = chunkZ * CHUNK_SIZE + CHUNK_SIZE / 2;
        group.position.set(cx, OVERLAY_Y, cz);

        selectionGroup.add(group);
        selectionMeshes.set(key, group);
    }

    function removeSelectionMesh(key) {
        const mesh = selectionMeshes.get(key);
        if (mesh) {
            selectionGroup.remove(mesh);
            selectionMeshes.delete(key);
        }
    }

    function clearSelectionMeshes() {
        if (selectionGroup) {
            while (selectionGroup.children.length > 0) {
                selectionGroup.remove(selectionGroup.children[0]);
            }
        }
        selectionMeshes.clear();
    }

    function rebuildAllMeshes() {
        clearSelectionMeshes();

        // Rebuild from selection
        for (const key of selection) {
            const [x, z] = key.split(",").map(Number);
            addSelectionMesh(key, x, z);
        }
    }

    // ── Selection Persistence ──────────────────────────────────

    function saveSelection() {
        try {
            var data = { worlds: {} };
            for (var wid of Object.keys(selections)) {
                if (selections[wid].size > 0) {
                    data.worlds[wid] = Array.from(selections[wid]);
                }
            }
            localStorage.setItem(STORAGE_KEY, JSON.stringify(data));
        } catch (e) {
            // localStorage full or unavailable
        }
    }

    function loadSelection() {
        try {
            var raw = localStorage.getItem(STORAGE_KEY);
            if (!raw) return;
            var data = JSON.parse(raw);

            // New world-keyed format: { worlds: { worldId: [...], ... } }
            if (data.worlds) {
                for (var wid of Object.keys(data.worlds)) {
                    selections[wid] = new Set(data.worlds[wid]);
                }
                return;
            }

            // Old flat format: { chunks: [...] }
            // Assign to current world (best effort; migrated later when scan data loads)
            if (data.chunks && Array.isArray(data.chunks)) {
                var wid = getCurrentWorldId() || "__unknown__";
                selections[wid] = new Set(data.chunks);
            }
        } catch (e) {
            // corrupt data, ignore
        }
    }

    // ── Export ──────────────────────────────────────────────────

    function exportJSON() {
        var wid = currentWorldId || "unknown";
        var chunks = Array.from(selection).map(function (key) {
            var parts = key.split(",").map(Number);
            var entry = { chunkX: parts[0], chunkZ: parts[1] };
            var chunk = getChunkData(key);
            if (chunk) {
                entry.inhabitedTime = chunk.it;
            }
            return entry;
        });

        var output = {
            dimension: wid,
            chunks: chunks
        };
        if (scanData && scanData.worlds[wid]) {
            output.worldName = scanData.worlds[wid].name;
            if (scanData.worlds[wid].seed != null) {
                output.worldSeed = scanData.worlds[wid].seed;
            }
        }

        var json = JSON.stringify(output, null, 2);
        download(json, "chunk-selection-" + wid + ".json", "application/json");
    }

    function exportMCASelectorCSV() {
        var wid = currentWorldId || "unknown";
        var csv = "";
        for (var key of selection) {
            var parts = key.split(",").map(Number);
            var cx = parts[0], cz = parts[1];
            var rx = Math.floor(cx / 32), rz = Math.floor(cz / 32);
            // MCA Selector format: regionX;regionZ;chunkX;chunkZ
            csv += rx + ";" + rz + ";" + cx + ";" + cz + "\n";
        }
        download(csv, "chunk-selection-" + wid + ".csv", "text/csv");
    }

    function download(content, filename, mimeType) {
        const blob = new Blob([content], { type: mimeType });
        const url = URL.createObjectURL(blob);
        const a = document.createElement("a");
        a.href = url;
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
    }

    // ── UI ─────────────────────────────────────────────────────

    let panelEl = null;
    let countEl = null;
    let toggleBtn = null;
    let spacerEl = null;

    function createUI() {
        createHeatmapToggle();
        createToggleButton();
        createPanel();
    }

    function createHeatmapToggle() {
        heatmapBtn = document.createElement("div");
        heatmapBtn.className = "svg-button";
        heatmapBtn.title = "Toggle Heatmap";
        // Thermometer icon
        heatmapBtn.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M14 14.76V3.5a2.5 2.5 0 0 0-5 0v11.26a4.5 4.5 0 1 0 5 0z"/>
        </svg>`;

        heatmapBtn.addEventListener("click", toggleHeatmap);

        // Insert into control bar
        const cb = document.querySelector(".control-bar");
        if (cb) {
            const ref = [...cb.children].find(
                (el) => el.className === "space thin-hide greedy"
            );
            if (ref) {
                spacerEl = document.createElement("div");
                spacerEl.className = "space thin-hide";
                ref.parentNode.insertBefore(spacerEl, ref);
                ref.parentNode.insertBefore(heatmapBtn, ref);
            } else {
                cb.appendChild(heatmapBtn);
            }
        }
    }

    function createToggleButton() {
        toggleBtn = document.createElement("div");
        toggleBtn.className = "svg-button";
        toggleBtn.title = "Chunk Trimmer";
        // Grid/selection icon
        toggleBtn.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <rect x="3" y="3" width="7" height="7"/>
            <rect x="14" y="3" width="7" height="7"/>
            <rect x="3" y="14" width="7" height="7"/>
            <rect x="14" y="14" width="7" height="7"/>
        </svg>`;

        toggleBtn.addEventListener("click", () => {
            active = !active;
            toggleBtn.classList.toggle("active", active);
            panelEl.style.display = active ? "block" : "none";

            if (active) {
                rebuildAllMeshes();
                updatePanel();
            } else {
                if (overlayEl) overlayEl.style.pointerEvents = "none";
                if (selectionGroup) clearSelectionMeshes();
            }
        });

        // Insert into control bar next to heatmap button
        if (heatmapBtn && heatmapBtn.parentNode) {
            heatmapBtn.parentNode.insertBefore(toggleBtn, heatmapBtn.nextSibling);
        } else {
            const cb = document.querySelector(".control-bar");
            if (cb) cb.appendChild(toggleBtn);
        }
    }

    function createPanel() {
        panelEl = document.createElement("div");
        panelEl.className = "chunk-trimmer-panel";
        panelEl.style.display = "none";
        panelEl.innerHTML = `
            <div class="ct-header">Chunk Trimmer</div>
            <div class="ct-dimension" id="ct-dimension"></div>
            <div class="ct-info">Ctrl+click/drag to select &bull; Shift+drag for area</div>
            <div class="ct-count">Selected: <span id="ct-count-num">0</span></div>
            <div class="ct-hover-info" id="ct-hover-info"></div>
            <div class="ct-buttons">
                <button id="ct-clear">Clear All</button>
                <button id="ct-export-json">Export JSON</button>
                <button id="ct-export-csv" title="Export as MCA Selector selection file (semicolon-delimited CSV)">Export CSV (MCA Selector)</button>
            </div>
        `;
        document.body.appendChild(panelEl);

        countEl = panelEl.querySelector("#ct-count-num");

        panelEl.querySelector("#ct-clear").addEventListener("click", () => {
            selection.clear();
            clearSelectionMeshes();
            saveSelection();
            updatePanel();
        });

        panelEl.querySelector("#ct-export-json").addEventListener("click", exportJSON);
        panelEl.querySelector("#ct-export-csv").addEventListener("click", exportMCASelectorCSV);
    }

    function updatePanel() {
        if (countEl) {
            countEl.textContent = selection.size;
        }
        var dimEl = document.getElementById("ct-dimension");
        if (dimEl) {
            dimEl.textContent = currentWorldId ? getDimensionLabel(currentWorldId) : "";
        }
    }

    // ── Boot ───────────────────────────────────────────────────

    init();
})();
