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
    let modifierHeld = false;

    // Selection: Set of "x,z" strings
    let selection = new Set();

    // Three.js objects for selection overlay
    const selectionMeshes = new Map(); // "x,z" -> Mesh
    let selectionGroup = null;

    // ── Constants ──────────────────────────────────────────────

    const CHUNK_SIZE = 16;
    const OVERLAY_Y = 65;
    const SELECTION_COLOR = 0xe64a19; // deep orange
    const SELECTION_OPACITY = 0.45;
    const STORAGE_KEY = "chunk-trimmer-selection";
    const DATA_PATH = "assets/chunk-trimmer/data.json";

    // Shared geometry for all selection meshes
    const chunkGeometry = new THREE.PlaneGeometry(CHUNK_SIZE, CHUNK_SIZE);
    chunkGeometry.rotateX(-Math.PI / 2); // lay flat on xz-plane

    const selectionMaterial = new THREE.MeshBasicMaterial({
        color: SELECTION_COLOR,
        transparent: true,
        opacity: SELECTION_OPACITY,
        side: THREE.DoubleSide,
        depthTest: false,
    });

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
        trackModifierKeys();
        listenForClicks();
        loadSelection();
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
                scanData = data;
                console.log(
                    "[ChunkTrimmer] Loaded scan data: " +
                        Object.keys(data.chunks || {}).length +
                        " chunks"
                );
            })
            .catch(() => {
                console.log("[ChunkTrimmer] No scan data available yet");
            });
    }

    // ── Modifier Key Tracking ──────────────────────────────────

    function trackModifierKeys() {
        document.addEventListener("keydown", (e) => {
            if (e.ctrlKey || e.metaKey) modifierHeld = true;
        });
        document.addEventListener("keyup", (e) => {
            if (!e.ctrlKey && !e.metaKey) modifierHeld = false;
        });
        // Reset on blur (user switches window while holding modifier)
        window.addEventListener("blur", () => {
            modifierHeld = false;
        });
    }

    // ── Click Handling ─────────────────────────────────────────

    function listenForClicks() {
        app.events.addEventListener("bluemapMapInteraction", (evt) => {
            if (!active || !modifierHeld) return;

            const hit = evt.detail.hiresHit || evt.detail.hit;
            if (!hit) return;

            const pos = hit.point;
            const chunkX = Math.floor(pos.x / CHUNK_SIZE);
            const chunkZ = Math.floor(pos.z / CHUNK_SIZE);
            const key = chunkX + "," + chunkZ;

            toggleChunk(key, chunkX, chunkZ);
        });
    }

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
            const data = {
                chunks: Array.from(selection),
            };
            localStorage.setItem(STORAGE_KEY, JSON.stringify(data));
        } catch (e) {
            // localStorage full or unavailable
        }
    }

    function loadSelection() {
        try {
            const raw = localStorage.getItem(STORAGE_KEY);
            if (!raw) return;
            const data = JSON.parse(raw);
            if (data.chunks && Array.isArray(data.chunks)) {
                selection = new Set(data.chunks);
            }
        } catch (e) {
            // corrupt data, ignore
        }
    }

    // ── Export ──────────────────────────────────────────────────

    function exportJSON() {
        const chunks = Array.from(selection).map((key) => {
            const [x, z] = key.split(",").map(Number);
            const entry = { chunkX: x, chunkZ: z };
            // Include scan data if available
            if (scanData && scanData.chunks && scanData.chunks[key]) {
                const sd = scanData.chunks[key];
                entry.inhabitedTime = sd.it;
                entry.tileEntities = sd.te;
                entry.hasPlayerBlocks = sd.pb;
            }
            return entry;
        });

        const json = JSON.stringify({ chunks: chunks }, null, 2);
        download(json, "chunk-selection.json", "application/json");
    }

    function exportCSV() {
        let csv = "chunkX,chunkZ\n";
        for (const key of selection) {
            csv += key.replace(",", ",") + "\n";
        }
        download(csv, "chunk-selection.csv", "text/csv");
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
            } else if (selectionGroup) {
                clearSelectionMeshes();
            }
        });

        // Insert into control bar
        const cb = document.querySelector(".control-bar");
        if (cb) {
            const ref = [...cb.children].find(
                (el) => el.className === "space thin-hide greedy"
            );
            if (ref) {
                const spacer = document.createElement("div");
                spacer.className = "space thin-hide";
                ref.parentNode.insertBefore(spacer, ref);
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
    }

    // ── Boot ───────────────────────────────────────────────────

    init();
})();
