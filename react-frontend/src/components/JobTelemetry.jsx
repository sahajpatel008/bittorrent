import { useMemo } from "react";

function JobTelemetry({ jobId, snapshot, compact = false }) {
  const job = useMemo(() => snapshot || null, [snapshot]);

  if (!jobId) {
    return null;
  }

  if (!job) {
    return <p className="feedback">Loading job details...</p>;
  }

  return (
    <div className={`job-telemetry ${compact ? "compact" : ""}`}>
      <div className="telemetry-header">
        <div>
          <h4>{job?.fileName || "Download job"}</h4>
          <p className="mono small">{job?.infoHash}</p>
        </div>
        <span className={`pill ${(job.status || "").toLowerCase()}`}>
          {job.status || "Unknown"}
        </span>
      </div>

      <div className="progress">
        <div className="progress-track">
          <div className="progress-bar" style={{ width: `${Number(job.progress ?? 0).toFixed(1)}%` }} />
        </div>
        <small>{Number(job.progress ?? 0).toFixed(1)}% complete</small>
      </div>

      <dl className="telemetry-stats">
        <div>
          <dt>Pieces</dt>
          <dd>{job.completedPieces ?? 0} / {job.totalPieces ?? 0}</dd>
        </div>
        <div>
          <dt>Speed</dt>
          <dd>{renderSpeed(job.overallDownloadSpeed)}</dd>
        </div>
        <div>
          <dt>Started</dt>
          <dd>{formatDate(job.startTime)}</dd>
        </div>
        <div>
          <dt>Updated</dt>
          <dd>{formatDate(job.lastUpdateTime)}</dd>
        </div>
      </dl>

      {!compact && (
        <>
          <section className="telemetry-section">
            <h5>Peer Stats</h5>
            {job.peers?.length ? (
              <div className="table-wrap">
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
                        <td>{peer.piecesDownloaded}/{peer.piecesUploaded}</td>
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
            <section className="telemetry-section">
              <h5>Piece Sources ({Object.keys(job.pieceSource).length} pieces)</h5>
              <dl className="piece-sources">
                {Object.entries(job.pieceSource)
                  .map(([piece, origin]) => (
                    <div key={piece}>
                      <dt>Piece {piece}</dt>
                      <dd className="mono">{origin}</dd>
                    </div>
                  ))}
              </dl>
            </section>
          )}
        </>
      )}

      {job.errorMessage && <p className="feedback error">{job.errorMessage}</p>}
    </div>
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

export default JobTelemetry;
