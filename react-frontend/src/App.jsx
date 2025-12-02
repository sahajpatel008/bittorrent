import { useCallback, useEffect, useMemo, useState } from "react";
import TorrentAnalyzer from "./components/TorrentAnalyzer.jsx";
import DownloadManager from "./components/DownloadManager.jsx";
import ActiveTransfers from "./components/ActiveTransfers.jsx";
import JobDetailDrawer from "./components/JobDetailDrawer.jsx";
import PeerTools from "./components/PeerTools.jsx";
import SeedingStation from "./components/SeedingStation.jsx";
import AlertStack from "./components/AlertStack.jsx";
import TorrentCreator from "./components/TorrentCreator.jsx";
import AddPeerModal from "./components/AddPeerModal.jsx";
import { API_BASE, request } from "./api.js";
import useInterval from "./hooks/useInterval.js";
import useEventSource from "./hooks/useEventSource.js";

function App() {
  const [torrents, setTorrents] = useState([]);
  const [loadingTorrents, setLoadingTorrents] = useState(false);
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [lastUpdated, setLastUpdated] = useState(null);
  const [selectedJobId, setSelectedJobId] = useState(null);
  const [jobSnapshots, setJobSnapshots] = useState({});
  const [alerts, setAlerts] = useState([]);
  const [addPeerModalInfoHash, setAddPeerModalInfoHash] = useState(null);
  const [activeView, setActiveView] = useState("analyzer");

  const pushAlert = useCallback((message, variant = "info") => {
    setAlerts((current) => [...current, { id: crypto.randomUUID(), message, variant }]);
  }, []);

  const dismissAlert = (id) => {
    setAlerts((current) => current.filter((alert) => alert.id !== id));
  };

  const refreshTorrents = useCallback(async () => {
    setLoadingTorrents(true);
    try {
      const list = await request("/torrents");
      setTorrents(list);
      setLastUpdated(Date.now());
    } catch (err) {
      pushAlert(`Failed to refresh torrents: ${err.message}`, "error");
    } finally {
      setLoadingTorrents(false);
    }
  }, [pushAlert]);

  useEffect(() => {
    refreshTorrents();
  }, [refreshTorrents]);

  useInterval(() => {
    refreshTorrents();
  }, 5000, autoRefresh);

  const handleJobUpdate = useCallback((payload) => {
    setJobSnapshots((current) => ({ ...current, [payload.jobId]: payload }));
    if (payload.status && payload.status !== "DOWNLOADING") {
      refreshTorrents();
    }
  }, [refreshTorrents]);

  // Centralized SSE subscription for the selected job - stays active regardless of view
  useEventSource(`${API_BASE}/torrents/download/${selectedJobId}/progress`, {
    enabled: Boolean(selectedJobId),
    onMessage: (event) => {
      try {
        const payload = JSON.parse(event.data);
        handleJobUpdate(payload);
      } catch (err) {
        pushAlert?.("Failed to parse progress event", "error");
      }
    }
  });

  // Fetch initial job status when a job is selected
  useEffect(() => {
    if (!selectedJobId) return;
    let mounted = true;
    request(`/torrents/download/${selectedJobId}/status`)
      .then((data) => {
        if (!mounted) return;
        handleJobUpdate(data);
      })
      .catch((err) => {
        if (!mounted) return;
        pushAlert(`Failed to load job status: ${err.message}`, "error");
      });
    return () => {
      mounted = false;
    };
  }, [selectedJobId, handleJobUpdate, pushAlert]);

  const selectedSnapshot = useMemo(() => (selectedJobId ? jobSnapshots[selectedJobId] : null), [jobSnapshots, selectedJobId]);

  const handleJobSelect = (jobId) => {
    setSelectedJobId(jobId);
  };

  const handleDeleteTorrent = useCallback(
    async (infoHash) => {
      if (!infoHash) return;
      try {
        await request(`/torrents/${infoHash}`, { method: "DELETE" });
        let clearSelection = false;
        setJobSnapshots((current) => {
          const next = { ...current };
          Object.entries(current).forEach(([jobId, snapshot]) => {
            if ((snapshot.infoHash ?? "").toLowerCase() === infoHash.toLowerCase()) {
              delete next[jobId];
              if (jobId === selectedJobId) {
                clearSelection = true;
              }
            }
          });
          return next;
        });
        if (clearSelection) {
          setSelectedJobId(null);
        }
        pushAlert(`Removed torrent ${infoHash}`, "success");
        refreshTorrents();
      } catch (err) {
        pushAlert(`Failed to remove torrent: ${err.message}`, "error");
      }
    },
    [pushAlert, refreshTorrents, selectedJobId]
  );

  const handleForceAnnounce = useCallback(
    async (infoHash) => {
      if (!infoHash) return;
      try {
        await request(`/torrents/${infoHash}/announce`, { method: "POST" });
        pushAlert(`Successfully announced torrent ${infoHash} to tracker`, "success");
        // Optionally refresh to show updated peer count
        setTimeout(() => refreshTorrents(), 1000);
      } catch (err) {
        pushAlert(`Failed to announce torrent: ${err.message}`, "error");
      }
    },
    [pushAlert, refreshTorrents]
  );

  const handleAddPeer = useCallback((infoHash) => {
    if (!infoHash) return;
    setAddPeerModalInfoHash(infoHash);
  }, []);

  const handleAddPeerSubmit = useCallback(
    async (infoHash, ip, port) => {
      try {
        await request(`/torrents/${infoHash}/peers`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ ip, port })
        });
        pushAlert(`Successfully added peer ${ip}:${port} to torrent`, "success");
        // Optionally refresh to show updated peer count
        setTimeout(() => refreshTorrents(), 1000);
      } catch (err) {
        throw new Error(err.message || "Failed to add peer");
      }
    },
    [pushAlert, refreshTorrents]
  );

  const renderActiveView = () => {
    switch (activeView) {
      case "analyzer":
        return <TorrentAnalyzer onInfoReady={(info) => pushAlert(`Info hash ${info.infoHash}`, "success")} pushAlert={pushAlert} />;
      case "download":
        return (
          <DownloadManager
            onJobCreated={(jobId) => handleJobSelect(jobId)}
            pushAlert={pushAlert}
            activeJobId={selectedJobId}
            jobSnapshot={selectedSnapshot}
          />
        );
      case "create":
        return <TorrentCreator pushAlert={pushAlert} />;
      case "transfers":
        return (
          <ActiveTransfers
            torrents={torrents}
            loading={loadingTorrents}
            lastUpdated={lastUpdated}
            autoRefresh={autoRefresh}
            onToggleAuto={setAutoRefresh}
            onRefresh={refreshTorrents}
            onInspect={handleJobSelect}
            onDelete={handleDeleteTorrent}
            onForceAnnounce={handleForceAnnounce}
            onAddPeer={handleAddPeer}
          />
        );
      case "peers":
        return <PeerTools pushAlert={pushAlert} />;
      case "seeding":
        return <SeedingStation pushAlert={pushAlert} onSeeding={() => refreshTorrents()} />;
      default:
        return <TorrentAnalyzer onInfoReady={(info) => pushAlert(`Info hash ${info.infoHash}`, "success")} pushAlert={pushAlert} />;
    }
  };

  return (
    <div className="app-shell">
      <header className="hero">
        <div>
          <p className="eyebrow">Distributed Systems · BitTorrent Client</p>
          <h1>BitTorrent Control Center</h1>
          <p>Full-stack view into torrents, peers, and swarms managed by your Spring Boot backend.</p>
        </div>
        <div className="hero-actions">
          <button className="ghost" onClick={refreshTorrents} disabled={loadingTorrents}>
            {loadingTorrents ? "Syncing..." : "Sync Now"}
          </button>
        </div>
      </header>

      <nav className="navbar">
        <button className={activeView === "analyzer" ? "nav-item active" : "nav-item"} onClick={() => setActiveView("analyzer")}>
          Torrent Analyzer
        </button>
        <button className={activeView === "download" ? "nav-item active" : "nav-item"} onClick={() => setActiveView("download")}>
          Download Manager
        </button>
        <button className={activeView === "create" ? "nav-item active" : "nav-item"} onClick={() => setActiveView("create")}>
          Torrent Creator
        </button>
        <button className={activeView === "transfers" ? "nav-item active" : "nav-item"} onClick={() => setActiveView("transfers")}>
          Active Transfers
        </button>
        <button className={activeView === "peers" ? "nav-item active" : "nav-item"} onClick={() => setActiveView("peers")}>
          Peer Tools
        </button>
        <button className={activeView === "seeding" ? "nav-item active" : "nav-item"} onClick={() => setActiveView("seeding")}>
          Seeding Station
        </button>
      </nav>

      <main className="view-container">
        {renderActiveView()}
      </main>

      <JobDetailDrawer
        jobId={activeView !== "download" ? selectedJobId : null}
        snapshot={selectedSnapshot}
        onClose={() => setSelectedJobId(null)}
        onViewFullDetails={() => setActiveView("download")}
      />
      <AlertStack alerts={alerts} onDismiss={dismissAlert} />
      {addPeerModalInfoHash && (
        <AddPeerModal
          infoHash={addPeerModalInfoHash}
          onClose={() => setAddPeerModalInfoHash(null)}
          onSubmit={handleAddPeerSubmit}
          pushAlert={pushAlert}
        />
      )}
    </div>
  );
}

export default App;
