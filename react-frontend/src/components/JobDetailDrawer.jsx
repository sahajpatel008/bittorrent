import { useEffect, useMemo, useState } from "react";
import { API_BASE, request } from "../api.js";
import useEventSource from "../hooks/useEventSource.js";

function JobDetailDrawer({ jobId, snapshot, onClose, onJobUpdate, pushAlert }) {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const job = useMemo(() => snapshot || null, [snapshot]);

  useEffect(() => {
    if (!jobId) return;
    let mounted = true;
    setLoading(true);
    setError(null);
    request(`/torrents/download/${jobId}/status`)
      .then((data) => {
        if (!mounted) return;
        onJobUpdate?.(data);
      })
      .catch((err) => {
        if (!mounted) return;
        setError(err.message);
      })
      .finally(() => mounted && setLoading(false));
    return () => {
      mounted = false;
    };
  }, [jobId, onJobUpdate]);

  useEventSource(`${API_BASE}/torrents/download/${jobId}/progress`, {
    enabled: Boolean(jobId),
    onMessage: (event) => {
      try {
        const payload = JSON.parse(event.data);
        onJobUpdate?.(payload);
      } catch (err) {
        pushAlert?.("Failed to parse progress event", "error");
      }
    }
  });

  if (!jobId) {
    return null;
  }

  return (
    <aside className="drawer">
      <div className="drawer-header">
        <div>
          <p className="eyebrow">Job Telemetry</p>
          <h3>{job?.fileName || "Download job"}</h3>
          <p className="mono">{job?.infoHash}</p>
        </div>
        <button className="ghost" onClick={onClose}>
          Close
        </button>
      </div>
      {loading && <p>Loading job details...</p>}
      {error && <p className="feedback error">{error}</p>}
      {job && (
        <>
          <div className="progress">
            <div className="progress-track">
              <div className="progress-bar" style={{ width: `${Number(job.progress ?? 0).toFixed(1)}%` }} />
            </div>
            <small>{Number(job.progress ?? 0).toFixed(1)}% complete</small>
          </div>
          <dl className="metrics-grid">
            {[
              ["Status", job.status],
              ["Pieces", `${job.completedPieces ?? 0} / ${job.totalPieces ?? 0}`],
              ["Overall speed", renderSpeed(job.overallDownloadSpeed)],
              ["Started", formatDate(job.startTime)],
              ["Updated", formatDate(job.lastUpdateTime)],
              ["Job ID", job.jobId]
            ].map(([label, value]) => (
              <div key={label}>
                <dt>{label}</dt>
                <dd>{value || "—"}</dd>
              </div>
            ))}
          </dl>
          <section>
            <h4>Peer stats</h4>
            {job.peers?.length ? (
              <div className="table-wrap compact">
                <table>
                  <thead>
                    <tr>
                      <th>Peer</th>
                      <th>Downloaded</th>
                      <th>Sent</th>
                      <th>Pieces</th>
                      <th>Speed</th>
                      <th>State</th>
                    </tr>
                  </thead>
                  <tbody>
                    {job.peers.map((peer) => (
                      <tr key={peer.address}>
                        <td className="mono">{peer.ip}:{peer.port}</td>
                        <td>{formatBytes(peer.bytesDownloaded)}</td>
                        <td>{formatBytes(peer.bytesUploaded)}</td>
                        <td>
                          {peer.piecesDownloaded}/{peer.piecesUploaded}
                        </td>
                        <td>{peer.downloadSpeed ? `${formatBytes(peer.downloadSpeed)}/s` : "—"}</td>
                        <td>{peer.isChoked ? "Choked" : "Open"}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : (
              <p className="feedback">Waiting for peers...</p>
            )}
          </section>
          {job.pieceSource && Object.keys(job.pieceSource).length > 0 && (
            <section>
              <h4>Latest piece sources</h4>
              <dl className="metrics-grid">
                {Object.entries(job.pieceSource)
                  .slice(-8)
                  .map(([piece, origin]) => (
                    <div key={piece}>
                      <dt>Piece {piece}</dt>
                      <dd className="mono">{origin}</dd>
                    </div>
                  ))}
              </dl>
            </section>
          )}
          {job.errorMessage && <p className="feedback error">{job.errorMessage}</p>}
        </>
      )}
    </aside>
  );
}

function formatBytes(bytes) {
  if (!Number.isFinite(bytes)) return "—";
  if (bytes === 0) return "0 B";
  const units = ["B", "KB", "MB", "GB", "TB"];
  const index = Math.floor(Math.log(bytes) / Math.log(1024));
  const value = bytes / Math.pow(1024, index);
  return `${value.toFixed(1)} ${units[index]}`;
}

function renderSpeed(value) {
  if (!Number.isFinite(value)) return "—";
  return `${formatBytes(value)}/s`;
}

function formatDate(timestamp) {
  if (!timestamp) return "—";
  return new Date(timestamp).toLocaleString();
}

export default JobDetailDrawer;
