const API_BASE = "/api";

const state = {
    torrents: [],
    jobs: {},
    selectedJobId: null,
    eventSources: {},
    autoRefresh: true,
};

const refs = {};

document.addEventListener("DOMContentLoaded", () => {
    cacheDom();
    bindEvents();
    pingBackend();
    fetchActiveTorrents();
    scheduleRefresh();
});

function cacheDom() {
    refs.serviceStatus = document.getElementById("serviceStatus");
    refs.refreshHealthBtn = document.getElementById("refreshHealthBtn");
    refs.torrentInfoForm = document.getElementById("torrentInfoForm");
    refs.torrentInfoFile = document.getElementById("torrentInfoFile");
    refs.torrentInfoResult = document.getElementById("torrentInfoResult");
    refs.clearInfoBtn = document.getElementById("clearInfoBtn");
    refs.downloadForm = document.getElementById("downloadForm");
    refs.downloadTorrentFile = document.getElementById("downloadTorrentFile");
    refs.outputFileName = document.getElementById("outputFileName");
    refs.downloadFeedback = document.getElementById("downloadFeedback");
    refs.activeTorrentsContainer = document.getElementById("activeTorrentsContainer");
    refs.refreshTorrentsBtn = document.getElementById("refreshTorrentsBtn");
    refs.autoRefreshToggle = document.getElementById("autoRefreshToggle");
    refs.jobDetails = document.getElementById("jobDetails");
    refs.peerInspectorForm = document.getElementById("peerInspectorForm");
    refs.peerInspectorHash = document.getElementById("peerInspectorHash");
    refs.peerInspectorResult = document.getElementById("peerInspectorResult");
    refs.addPeerForm = document.getElementById("addPeerForm");
    refs.addPeerHash = document.getElementById("addPeerHash");
    refs.addPeerIp = document.getElementById("addPeerIp");
    refs.addPeerPort = document.getElementById("addPeerPort");
    refs.addPeerResult = document.getElementById("addPeerResult");
    refs.seedForm = document.getElementById("seedForm");
    refs.seedResult = document.getElementById("seedResult");
    refs.seedTorrentFile = document.getElementById("seedTorrentFile");
    refs.seedDataFile = document.getElementById("seedDataFile");
    refs.alertTray = document.getElementById("alertTray");
}

function bindEvents() {
    refs.refreshHealthBtn.addEventListener("click", pingBackend);
    refs.clearInfoBtn.addEventListener("click", () => {
        refs.torrentInfoForm.reset();
        refs.torrentInfoResult.innerHTML = "";
    });
    refs.torrentInfoForm.addEventListener("submit", handleTorrentInfo);
    refs.downloadForm.addEventListener("submit", handleDownloadStart);
    refs.refreshTorrentsBtn.addEventListener("click", fetchActiveTorrents);
    refs.autoRefreshToggle.addEventListener("change", (event) => {
        state.autoRefresh = event.target.checked;
    });
    refs.peerInspectorForm.addEventListener("submit", handlePeerLookup);
    refs.addPeerForm.addEventListener("submit", handleAddPeer);
    refs.seedForm.addEventListener("submit", handleSeedStart);
    refs.activeTorrentsContainer.addEventListener("click", (event) => {
        const button = event.target.closest("[data-job]");
        if (button) {
            selectJob(button.dataset.job);
        }
    });
}

function scheduleRefresh() {
    setInterval(() => {
        if (state.autoRefresh) {
            fetchActiveTorrents();
        }
    }, 5000);

    setInterval(() => {
        pingBackend({ silent: true });
    }, 30000);
}

async function pingBackend(options = {}) {
    try {
        const response = await fetchJson(`${API_BASE}/`);
        updateStatusIndicator(true, response.message ?? "API healthy");
        if (!options.silent) {
            toast("Backend reachable", "success");
        }
    } catch (error) {
        updateStatusIndicator(false, error.message);
        if (!options.silent) {
            toast("Backend check failed", "error");
        }
    }
}

function updateStatusIndicator(isOnline, message) {
    refs.serviceStatus.textContent = message;
    refs.serviceStatus.classList.toggle("status-online", isOnline);
    refs.serviceStatus.classList.toggle("status-offline", !isOnline);
}

async function handleTorrentInfo(event) {
    event.preventDefault();
    if (!refs.torrentInfoFile.files.length) {
        return;
    }
    const formData = new FormData();
    formData.append("file", refs.torrentInfoFile.files[0]);
    try {
        const info = await postFormData(`${API_BASE}/torrents/info`, formData);
        renderTorrentInfo(info);
        refs.peerInspectorHash.value = info.infoHash ?? "";
        refs.addPeerHash.value = info.infoHash ?? "";
        toast("Metadata loaded", "success");
    } catch (error) {
        refs.torrentInfoResult.innerHTML = `<p class="feedback error">${error.message}</p>`;
        toast("Failed to inspect torrent", "error");
    }
}

function renderTorrentInfo(info) {
    if (!info) {
        refs.torrentInfoResult.innerHTML = "";
        return;
    }
    const fields = [
        ["Name", info.name ?? "n/a"],
        ["Tracker", info.trackerUrl ?? info.announce ?? "n/a"],
        ["Length", formatBytes(info.length)],
        ["Piece length", formatBytes(info.pieceLength)],
        ["Pieces", info.pieceCount ?? info.pieces?.length ?? 0],
        ["Info hash", info.infoHash ?? "n/a"],
    ];
    refs.torrentInfoResult.innerHTML = fields
        .map(
            ([label, value]) => `
            <div class="metric">
                <span>${label}</span>
                <strong>${value}</strong>
            </div>`
        )
        .join("");
}

async function handleDownloadStart(event) {
    event.preventDefault();
    if (!refs.downloadTorrentFile.files.length) return;

    setFeedback(refs.downloadFeedback, "Starting download...");
    const formData = new FormData();
    formData.append("file", refs.downloadTorrentFile.files[0]);
    if (refs.outputFileName.value.trim()) {
        formData.append("outputFileName", refs.outputFileName.value.trim());
    }

    try {
        const response = await postFormData(`${API_BASE}/torrents/download`, formData);
        setFeedback(refs.downloadFeedback, `Job ${response.jobId} started`, "success");
        toast("Download job created", "success");
        selectJob(response.jobId);
        fetchActiveTorrents();
        refs.downloadForm.reset();
    } catch (error) {
        setFeedback(refs.downloadFeedback, error.message, "error");
        toast("Failed to start download", "error");
    }
}

async function fetchActiveTorrents() {
    refs.activeTorrentsContainer.innerHTML = "<p>Loading torrents...</p>";
    try {
        const torrents = await fetchJson(`${API_BASE}/torrents`);
        state.torrents = torrents;
        renderActiveTorrents();
    } catch (error) {
        refs.activeTorrentsContainer.innerHTML = `<p class="feedback error">${error.message}</p>`;
    }
}

function renderActiveTorrents() {
    if (!state.torrents.length) {
        refs.activeTorrentsContainer.innerHTML = "<p class=\"feedback\">No active torrents right now.</p>";
        return;
    }

    const rows = state.torrents
        .map((torrent) => {
            const rawStatus = (torrent.status ?? "UNKNOWN").toString();
            const statusClass = rawStatus.toLowerCase();
            const isDownload = torrent.type === "downloading" && torrent.jobId;
            const progress = torrent.progress ? torrent.progress.toFixed(1) : "0";
            const action = isDownload
                ? `<button class="ghost-button" data-job="${torrent.jobId}">Inspect Job</button>`
                : "";
            return `
                <tr>
                    <td>
                        <div class="file-name">${torrent.fileName ?? "unknown"}</div>
                        <small class="muted">${torrent.infoHash ?? ""}</small>
                    </td>
                    <td><span class="status-pill status-${statusClass}">${rawStatus}</span></td>
                    <td>${progress}%</td>
                    <td>${torrent.completedPieces ?? "-"} / ${torrent.totalPieces ?? "-"}</td>
                    <td>${torrent.downloadSpeed ? formatBytes(torrent.downloadSpeed) + "/s" : "-"}</td>
                    <td>${torrent.type ?? "-"}</td>
                    <td>${action}</td>
                </tr>
            `;
        })
        .join("");

    refs.activeTorrentsContainer.innerHTML = `
        <table>
            <thead>
                <tr>
                    <th>File</th>
                    <th>Status</th>
                    <th>Progress</th>
                    <th>Pieces</th>
                    <th>Speed</th>
                    <th>Role</th>
                    <th></th>
                </tr>
            </thead>
            <tbody>${rows}</tbody>
        </table>
    `;
}

async function selectJob(jobId) {
    if (!jobId) return;
    state.selectedJobId = jobId;
    refs.jobDetails.innerHTML = `<p>Loading job ${jobId}...</p>`;
    try {
        const data = await fetchJson(`${API_BASE}/torrents/download/${jobId}/status`);
        updateJobState(jobId, data);
        renderJobDetails();
        subscribeToJob(jobId);
    } catch (error) {
        refs.jobDetails.innerHTML = `<p class="feedback error">${error.message}</p>`;
    }
}

function updateJobState(jobId, payload) {
    state.jobs[jobId] = { ...(state.jobs[jobId] ?? {}), ...payload };
    const status = (payload.status ?? "").toUpperCase();
    if (status && status !== "DOWNLOADING") {
        closeEventSource(jobId);
    }
}

function renderJobDetails() {
    const job = state.jobs[state.selectedJobId];
    if (!job) {
        refs.jobDetails.innerHTML = "<p>Select a download job to inspect.</p>";
        return;
    }
    const statusRaw = job.status ?? "unknown";
    const normalizedStatus = statusRaw.toUpperCase();
    const statusClass = `status-pill status-${statusRaw.toLowerCase()}`;
    const progress = Number(job.progress ?? 0);
    const metrics = [
        ["Job ID", job.jobId],
        ["Info hash", job.infoHash],
        ["Pieces", `${job.completedPieces ?? 0} / ${job.totalPieces ?? 0}`],
        ["Overall speed", job.overallDownloadSpeed ? `${formatBytes(job.overallDownloadSpeed)}/s` : "-"],
        ["Started", job.startTime ? new Date(job.startTime).toLocaleString() : "-"],
        ["Last update", job.lastUpdateTime ? new Date(job.lastUpdateTime).toLocaleString() : "-"],
    ];

    const downloadLink =
        normalizedStatus === "COMPLETED"
            ? `<a class="ghost-button" href="${API_BASE}/torrents/download/${job.jobId}/file">Download file</a>`
            : "";

    const peers = job.peers ?? [];
    const peerRows = peers
        .map(
            (peer) => `
            <tr>
                <td>${peer.ip}:${peer.port}</td>
                <td>${formatBytes(peer.bytesDownloaded)} / ${formatBytes(peer.bytesUploaded)}</td>
                <td>${peer.piecesDownloaded}/${peer.piecesUploaded}</td>
                <td>${peer.downloadSpeed ? `${formatBytes(peer.downloadSpeed)}/s` : "-"}</td>
                <td>${peer.isChoked ? "Choked" : "Open"}</td>
            </tr>
        `
        )
        .join("");

    const pieceSource = job.pieceSource
        ? Object.entries(job.pieceSource)
              .slice(-12)
              .map(([piece, source]) => `<div class="metric"><span>Piece ${piece}</span><strong>${source}</strong></div>`)
              .join("")
        : "";

    refs.jobDetails.innerHTML = `
        <div class="job-header">
            <div>
                <h3>${job.fileName ?? "Unnamed download"}</h3>
                <p class="muted">${job.infoHash ?? ""}</p>
            </div>
            <div>
                <span class="${statusClass}">${normalizedStatus}</span>
                ${downloadLink}
            </div>
        </div>
        <div>
            <div class="progress-track">
                <div class="progress-bar" style="width:${progress}%"></div>
            </div>
            <small>${progress.toFixed(1)}% complete</small>
        </div>
        <div class="job-grid">
            ${metrics
                .map(
                    ([label, value]) => `
                <div class="metric">
                    <span>${label}</span>
                    <strong>${value ?? "-"}</strong>
                </div>`
                )
                .join("")}
        </div>
        <div>
            <h4>Peer stats</h4>
            ${
                peers.length
                    ? `<div class="table-scroll peer-table">
                        <table>
                            <thead>
                                <tr>
                                    <th>Peer</th>
                                    <th>Transferred</th>
                                    <th>Pieces</th>
                                    <th>Speed</th>
                                    <th>State</th>
                                </tr>
                            </thead>
                            <tbody>${peerRows}</tbody>
                        </table>
                       </div>`
                    : "<p class=\"feedback\">No peers recorded yet.</p>"
            }
        </div>
        ${
            pieceSource
                ? `<div>
                        <h4>Recent piece sources</h4>
                        <div class="info-grid small">${pieceSource}</div>
                   </div>`
                : ""
        }
        ${
            job.error
                ? `<div class="feedback error">Error: ${job.error}</div>`
                : job.errorMessage
                ? `<div class="feedback error">Error: ${job.errorMessage}</div>`
                : ""
        }
    `;
}

function subscribeToJob(jobId) {
    if (state.eventSources[jobId]) return;
    const source = new EventSource(`${API_BASE}/torrents/download/${jobId}/progress`);
    source.addEventListener("progress", (event) => {
        try {
            const payload = JSON.parse(event.data);
            updateJobState(jobId, payload);
            if (state.selectedJobId === jobId) {
                renderJobDetails();
            }
        } catch (error) {
            console.error("Failed to parse progress event", error);
        }
    });
    source.onerror = () => {
        closeEventSource(jobId);
    };
    state.eventSources[jobId] = source;
}

function closeEventSource(jobId) {
    const source = state.eventSources[jobId];
    if (source) {
        source.close();
        delete state.eventSources[jobId];
    }
}

async function handlePeerLookup(event) {
    event.preventDefault();
    const infoHash = refs.peerInspectorHash.value.trim();
    if (!infoHash) return;
    refs.peerInspectorResult.innerHTML = "<p>Loading peers...</p>";
    try {
        const response = await fetchJson(`${API_BASE}/torrents/${infoHash}/peers`);
        const peers = response.peers ?? [];
        refs.peerInspectorResult.innerHTML = peers.length
            ? peers
                  .map(
                      (peer) => `
                    <div class="metric">
                        <span>${peer.ip}</span>
                        <strong>${peer.port}</strong>
                    </div>`
                  )
                  .join("")
            : "<p class=\"feedback\">No peers available.</p>";
        toast(`Fetched ${peers.length} peers`, "success");
    } catch (error) {
        refs.peerInspectorResult.innerHTML = `<p class="feedback error">${error.message}</p>`;
        toast("Failed to load peers", "error");
    }
}

async function handleAddPeer(event) {
    event.preventDefault();
    const payload = {
        ip: refs.addPeerIp.value.trim(),
        port: Number(refs.addPeerPort.value),
    };
    const infoHash = refs.addPeerHash.value.trim();
    setFeedback(refs.addPeerResult, "Submitting peer...");
    try {
        const response = await fetchJson(`${API_BASE}/torrents/${infoHash}/peers`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload),
        });
        setFeedback(refs.addPeerResult, response.message ?? "Peer added.", "success");
        toast("Peer added to swarm", "success");
    } catch (error) {
        setFeedback(refs.addPeerResult, error.message, "error");
        toast("Failed to add peer", "error");
    }
}

async function handleSeedStart(event) {
    event.preventDefault();
    if (!refs.seedTorrentFile.files.length || !refs.seedDataFile.files.length) return;
    const formData = new FormData();
    formData.append("torrent", refs.seedTorrentFile.files[0]);
    formData.append("file", refs.seedDataFile.files[0]);
    setFeedback(refs.seedResult, "Starting seeding session...");
    try {
        const response = await postFormData(`${API_BASE}/torrents/seed`, formData);
        setFeedback(refs.seedResult, `Seeding: ${response.infoHash}`, "success");
        toast("Seeding started", "success");
        refs.seedForm.reset();
        fetchActiveTorrents();
    } catch (error) {
        setFeedback(refs.seedResult, error.message, "error");
        toast("Failed to seed torrent", "error");
    }
}

async function fetchJson(url, options = {}) {
    const response = await fetch(url, options);
    if (!response.ok) {
        const text = await response.text();
        throw new Error(parseError(text, response.status));
    }
    const contentType = response.headers.get("content-type");
    if (contentType && contentType.includes("application/json")) {
        return response.json();
    }
    return response.text();
}

async function postFormData(url, formData) {
    const response = await fetch(url, {
        method: "POST",
        body: formData,
    });
    if (!response.ok) {
        const text = await response.text();
        throw new Error(parseError(text, response.status));
    }
    return response.json();
}

function parseError(payload, status) {
    try {
        const data = JSON.parse(payload);
        return data.error ?? JSON.stringify(data);
    } catch {
        return payload || `Request failed (${status})`;
    }
}

function formatBytes(bytes) {
    if (!Number.isFinite(bytes)) return "-";
    if (bytes === 0) return "0 B";
    const units = ["B", "KB", "MB", "GB", "TB"];
    const index = Math.floor(Math.log(bytes) / Math.log(1024));
    const value = bytes / Math.pow(1024, index);
    return `${value.toFixed(1)} ${units[index]}`;
}

function setFeedback(element, text, variant = "info") {
    if (!element) return;
    element.textContent = text;
    element.classList.remove("error", "success");
    if (variant === "error") element.classList.add("error");
    if (variant === "success") element.classList.add("success");
}

function toast(message, variant = "success") {
    const alert = document.createElement("div");
    alert.className = `alert ${variant}`;
    alert.textContent = message;
    refs.alertTray.appendChild(alert);
    setTimeout(() => {
        alert.remove();
    }, 4000);
}
