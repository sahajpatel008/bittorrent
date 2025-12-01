import { useState } from "react";
import { request } from "../api.js";

function PeerTools({ pushAlert }) {
  const [infoHash, setInfoHash] = useState("");
  const [peers, setPeers] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  const [manualHash, setManualHash] = useState("");
  const [peerIp, setPeerIp] = useState("");
  const [peerPort, setPeerPort] = useState(6881);
  const [manualStatus, setManualStatus] = useState(null);

  const fetchPeers = async (event) => {
    event.preventDefault();
    if (!infoHash.trim()) return;
    setLoading(true);
    setError(null);
    try {
      const result = await request(`/torrents/${infoHash.trim()}/peers`);
      setPeers(result.peers || []);
      pushAlert?.(`Fetched ${result.count ?? result.peers?.length ?? 0} peers.`, "success");
    } catch (err) {
      setError(err.message);
      setPeers([]);
      pushAlert?.("Failed to fetch peers.", "error");
    } finally {
      setLoading(false);
    }
  };

  const addPeer = async (event) => {
    event.preventDefault();
    setManualStatus("Submitting peer...");
    try {
      const payload = { ip: peerIp.trim(), port: Number(peerPort) };
      const result = await request(`/torrents/${manualHash.trim()}/peers`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload)
      });
      setManualStatus(result.message || "Peer added.");
      pushAlert?.("Peer added successfully.", "success");
    } catch (err) {
      setManualStatus(err.message);
      pushAlert?.("Failed to add peer.", "error");
    }
  };

  return (
    <section className="panel">
      <header>
        <h2>Peer & Tracker Tools</h2>
        <p>Inspect swarm membership or manually add a peer when trackers are unavailable.</p>
      </header>
      <form className="stack" onSubmit={fetchPeers}>
        <label>
          <span className="label">Info hash</span>
          <input type="text" value={infoHash} onChange={(event) => setInfoHash(event.target.value)} required />
        </label>
        <button className="ghost" type="submit" disabled={loading}>
          {loading ? "Fetching..." : "Fetch Peers"}
        </button>
      </form>
      {error && <p className="feedback error">{error}</p>}
      {peers.length > 0 && (
        <dl className="metrics-grid small">
          {peers.map((peer) => (
            <div key={`${peer.ip}-${peer.port}`}>
              <dt>{peer.ip}</dt>
              <dd>{peer.port}</dd>
            </div>
          ))}
        </dl>
      )}
      <hr />
      <form className="stack" onSubmit={addPeer}>
        <label>
          <span className="label">Info hash</span>
          <input type="text" value={manualHash} onChange={(e) => setManualHash(e.target.value)} required />
        </label>
        <div className="grid-2">
          <label>
            <span className="label">Peer IP</span>
            <input type="text" value={peerIp} onChange={(e) => setPeerIp(e.target.value)} required />
          </label>
          <label>
            <span className="label">Peer port</span>
            <input type="number" value={peerPort} onChange={(e) => setPeerPort(e.target.value)} min="1" max="65535" required />
          </label>
        </div>
        <button className="primary" type="submit">
          Add Peer
        </button>
        {manualStatus && <p className="feedback">{manualStatus}</p>}
      </form>
    </section>
  );
}

export default PeerTools;
