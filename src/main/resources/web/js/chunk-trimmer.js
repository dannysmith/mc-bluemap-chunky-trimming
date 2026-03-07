/**
 * BlueMap Chunk Trimmer — Web Addon
 *
 * Adds interactive chunk selection to BlueMap:
 * - Toggle button in control bar
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
    let heatmapVisible = true;
    let inFlatView = true;
    let savedMarkerVisibility = {}; // label -> boolean, saved when leaving flat

    // Three.js objects for selection overlay
    const selectionMeshes = new Map(); // "x,z" -> Mesh
    let selectionGroup = null;
    let overlayEl = null;
    let hudEl = null;
    let lastHoverKey = null;

    // ── Constants ──────────────────────────────────────────────

    const CHUNK_SIZE = 16;
    const OVERLAY_Y = 65;
    const SELECTION_COLOR = 0x00bcd4; // cyan — distinct from heatmap green/yellow/red
    const STORAGE_KEY = "chunk-trimmer-selection";
    const DATA_PATH = "assets/chunk-trimmer/data.json";

    // Shared geometry for all selection meshes
    const chunkGeometry = new THREE.PlaneGeometry(CHUNK_SIZE, CHUNK_SIZE);
    chunkGeometry.rotateX(-Math.PI / 2); // lay flat on xz-plane

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

        // Diagonal stripes in cyan
        ctx.strokeStyle = "rgba(0, 188, 212, 0.55)";
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
            heatmapVisible = checkHeatmapVisible();
            var flat = isFlatView();
            if (flat !== inFlatView) onViewModeChanged(flat);
            // Keep hiding our markers each cycle in non-flat mode,
            // in case BlueMap re-enabled them (e.g. sidebar toggle click)
            if (!inFlatView) setOurMarkersVisible(false);
        }, 500);
    }

    /**
     * Checks if the heatmap marker set is toggled visible in BlueMap's sidebar.
     * Searches the Three.js scene graph for the marker set by label.
     */
    function checkHeatmapVisible() {
        try {
            var root = app.mapViewer.markers;
            for (var i = 0; i < root.children.length; i++) {
                var child = root.children[i];
                if (child === selectionGroup) continue;
                if (isHeatmapSet(child)) return child.visible;
                // Marker sets may be nested under a marker file manager
                for (var j = 0; j < child.children.length; j++) {
                    if (isHeatmapSet(child.children[j])) return child.children[j].visible;
                }
            }
        } catch (e) {}
        return true;
    }

    function isHeatmapSet(obj) {
        try {
            if (obj.data && typeof obj.data.label === "string" &&
                obj.data.label.indexOf("Inhabited Time") >= 0) return true;
        } catch (e) {}
        return false;
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

    function isOurMarkerSet(obj) {
        try {
            if (obj.data && typeof obj.data.label === "string") {
                var label = obj.data.label;
                return label.indexOf("Inhabited Time") >= 0 ||
                       label.indexOf("Player Modified") >= 0;
            }
        } catch (e) {}
        return false;
    }

    function setOurMarkersVisible(visible) {
        try {
            var root = app.mapViewer.markers;
            for (var i = 0; i < root.children.length; i++) {
                var child = root.children[i];
                if (child === selectionGroup) continue;
                if (isOurMarkerSet(child)) { child.visible = visible; continue; }
                for (var j = 0; j < child.children.length; j++) {
                    if (isOurMarkerSet(child.children[j])) {
                        child.children[j].visible = visible;
                    }
                }
            }
        } catch (e) {}
    }

    /** Save current .visible state of our marker sets before we override them. */
    function saveOurMarkerVisibility() {
        try {
            var root = app.mapViewer.markers;
            for (var i = 0; i < root.children.length; i++) {
                var child = root.children[i];
                if (child === selectionGroup) continue;
                if (isOurMarkerSet(child)) {
                    savedMarkerVisibility[child.data.label] = child.visible;
                    continue;
                }
                for (var j = 0; j < child.children.length; j++) {
                    if (isOurMarkerSet(child.children[j])) {
                        savedMarkerVisibility[child.children[j].data.label] = child.children[j].visible;
                    }
                }
            }
        } catch (e) {}
    }

    /** Restore saved .visible state, defaulting to true if no saved state. */
    function restoreOurMarkerVisibility() {
        try {
            var root = app.mapViewer.markers;
            for (var i = 0; i < root.children.length; i++) {
                var child = root.children[i];
                if (child === selectionGroup) continue;
                if (isOurMarkerSet(child)) {
                    child.visible = savedMarkerVisibility[child.data.label] !== false;
                    continue;
                }
                for (var j = 0; j < child.children.length; j++) {
                    if (isOurMarkerSet(child.children[j])) {
                        child.children[j].visible = savedMarkerVisibility[child.children[j].data.label] !== false;
                    }
                }
            }
        } catch (e) {}
    }

    function onViewModeChanged(flat) {
        inFlatView = flat;
        if (flat) {
            if (toggleBtn) toggleBtn.style.display = "";
            if (spacerEl) spacerEl.style.display = "";
            if (active && panelEl) panelEl.style.display = "block";
            if (active) rebuildAllMeshes();
            restoreOurMarkerVisibility();
        } else {
            if (toggleBtn) toggleBtn.style.display = "none";
            if (spacerEl) spacerEl.style.display = "none";
            if (panelEl) panelEl.style.display = "none";
            if (overlayEl) overlayEl.style.pointerEvents = "none";
            clearSelectionMeshes();
            saveOurMarkerVisibility();
            setOurMarkersVisible(false);
            hideHud();
        }
    }

    // ── Click Handling ─────────────────────────────────────────

    // Ground plane for raycasting (Y = OVERLAY_Y, normal pointing up)
    const groundPlane = new THREE.Plane(new THREE.Vector3(0, 1, 0), -OVERLAY_Y);

    /**
     * Creates a transparent DOM overlay on top of the canvas.
     * When selection mode is active and Ctrl/Cmd is held, the overlay
     * captures pointer events before they reach BlueMap's canvas,
     * preventing heatmap ShapeMarkers from intercepting selection clicks.
     *
     * Previous approaches (document-level capture-phase stopImmediatePropagation
     * on pointerup/click) failed because BlueMap uses internal Three.js raycasting
     * that bypasses DOM event propagation entirely.
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

        // Overlay click handling
        let downPos = null;

        overlayEl.addEventListener("pointerdown", (e) => {
            if (e.button !== 0) return;
            downPos = { x: e.clientX, y: e.clientY };
            e.preventDefault();
        });

        overlayEl.addEventListener("pointerup", (e) => {
            if (e.button !== 0 || !downPos) return;

            const dx = e.clientX - downPos.x;
            const dy = e.clientY - downPos.y;
            downPos = null;

            // Ignore drags (> 5px movement)
            if (Math.sqrt(dx * dx + dy * dy) > 5) return;

            e.preventDefault();

            const pos = worldPosFromMouse(e);
            if (!pos) return;

            const chunkX = Math.floor(pos.x / CHUNK_SIZE);
            const chunkZ = Math.floor(pos.z / CHUNK_SIZE);
            toggleChunk(chunkX + "," + chunkZ, chunkX, chunkZ);
        });

        // Ctrl/Cmd key tracking: overlay only intercepts when modifier held
        document.addEventListener("keydown", (e) => {
            if (!active || !overlayEl) return;
            if (e.key === "Control" || e.key === "Meta") {
                overlayEl.style.pointerEvents = "auto";
            }
        });

        document.addEventListener("keyup", (e) => {
            if (!overlayEl) return;
            if (e.key === "Control" || e.key === "Meta") {
                overlayEl.style.pointerEvents = "none";
            }
        });

        // Safety: reset if window loses focus while modifier held
        window.addEventListener("blur", () => {
            if (overlayEl) overlayEl.style.pointerEvents = "none";
        });
    }

    function worldPosFromMouse(e) {
        const canvas = app.mapViewer.renderer.domElement;
        const rect = canvas.getBoundingClientRect();
        const mouse = new THREE.Vector2(
            ((e.clientX - rect.left) / rect.width) * 2 - 1,
            -((e.clientY - rect.top) / rect.height) * 2 + 1
        );
        const raycaster = new THREE.Raycaster();
        raycaster.setFromCamera(mouse, app.mapViewer.camera);
        const intersection = new THREE.Vector3();
        return raycaster.ray.intersectPlane(groundPlane, intersection)
            ? intersection
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
                entry.tileEntities = chunk.te;
                entry.hasPlayerBlocks = chunk.pb;
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

    function exportCSV() {
        var wid = currentWorldId || "unknown";
        var csv = "# dimension: " + wid + "\n";
        csv += "chunkX,chunkZ\n";
        for (var key of selection) {
            csv += key.replace(",", ",") + "\n";
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
        createToggleButton();
        createPanel();
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
                ref.parentNode.insertBefore(toggleBtn, ref);
            } else {
                cb.appendChild(toggleBtn);
            }
        }
    }

    function createPanel() {
        panelEl = document.createElement("div");
        panelEl.className = "chunk-trimmer-panel";
        panelEl.style.display = "none";
        panelEl.innerHTML = `
            <div class="ct-header">Chunk Trimmer</div>
            <div class="ct-dimension" id="ct-dimension"></div>
            <div class="ct-info">Ctrl+click to select chunks</div>
            <div class="ct-count">Selected: <span id="ct-count-num">0</span></div>
            <div class="ct-hover-info" id="ct-hover-info"></div>
            <div class="ct-buttons">
                <button id="ct-clear">Clear All</button>
                <button id="ct-export-json">Export JSON</button>
                <button id="ct-export-csv">Export CSV</button>
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
        panelEl.querySelector("#ct-export-csv").addEventListener("click", exportCSV);
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
