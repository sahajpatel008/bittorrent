import { useCallback, useEffect, useMemo, useState } from "react";
import TorrentAnalyzer from "./components/TorrentAnalyzer.jsx";
import DownloadManager from "./components/DownloadManager.jsx";
import ActiveTransfers from "./components/ActiveTransfers.jsx";
import JobDetailDrawer from "./components/JobDetailDrawer.jsx";
import PeerTools from "./components/PeerTools.jsx";
import SeedingStation from "./components/SeedingStation.jsx";
import AlertStack from "./components/AlertStack.jsx";
import TorrentCreator from "./components/TorrentCreator.jsx";
import { request } from "./api.js";
import useInterval from "./hooks/useInterval.js";

function App() {
  const [torrents, setTorrents] = useState([]);
  const [loadingTorrents, setLoadingTorrents] = useState(false);
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [lastUpdated, setLastUpdated] = useState(null);
  const [selectedJobId, setSelectedJobId] = useState(null);
  const [jobSnapshots, setJobSnapshots] = useState({});
  const [alerts, setAlerts] = useState([]);

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

      <main className="grid">
        <TorrentAnalyzer onInfoReady={(info) => pushAlert(`Info hash ${info.infoHash}`, "success")} pushAlert={pushAlert} />
        <DownloadManager onJobCreated={(jobId) => handleJobSelect(jobId)} pushAlert={pushAlert} />
        <TorrentCreator pushAlert={pushAlert} />
        <ActiveTransfers
          torrents={torrents}
          loading={loadingTorrents}
          lastUpdated={lastUpdated}
          autoRefresh={autoRefresh}
          onToggleAuto={setAutoRefresh}
          onRefresh={refreshTorrents}
          onInspect={handleJobSelect}
          onDelete={handleDeleteTorrent}
        />
        <PeerTools pushAlert={pushAlert} />
        <SeedingStation pushAlert={pushAlert} onSeeding={() => refreshTorrents()} />
      </main>

      <JobDetailDrawer
        jobId={selectedJobId}
        snapshot={selectedSnapshot}
        onClose={() => setSelectedJobId(null)}
        onJobUpdate={handleJobUpdate}
        pushAlert={pushAlert}
      />
      <AlertStack alerts={alerts} onDismiss={dismissAlert} />
    </div>
  );
}

export default App;
