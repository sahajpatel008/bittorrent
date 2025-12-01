import clsx from "clsx";

function ActiveTransfers({
  torrents,
  loading,
  lastUpdated,
  autoRefresh,
  onToggleAuto,
  onRefresh,
  onInspect
}) {
  return (
    <section className="panel">
      <header className="panel-header">
        <div>
          <h2>Active Transfers</h2>
          <p>Seeding and downloading torrents currently tracked by the backend.</p>
        </div>
        <div className="toolbar">
          <label className="toggle">
            <input type="checkbox" checked={autoRefresh} onChange={(event) => onToggleAuto(event.target.checked)} />
            Auto refresh
          </label>
          <button className="ghost" onClick={onRefresh} disabled={loading}>
            {loading ? "Refreshing..." : "Refresh"}
          </button>
        </div>
      </header>
      <div className="table-wrap">
        {loading ? (
          <p>Loading torrents...</p>
        ) : torrents.length ? (
          <table>
            <thead>
              <tr>
                <th>File</th>
                <th>Status</th>
                <th>Progress</th>
                <th>Pieces</th>
                <th>Speed</th>
                <th>Role</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {torrents.map((torrent) => {
                const status = (torrent.status || "UNKNOWN").toString();
                const progress = Number(torrent.progress ?? 0).toFixed(1);
                const downloadSpeed = torrent.downloadSpeed ? `${formatBytes(torrent.downloadSpeed)}/s` : "—";
                return (
                  <tr key={`${torrent.infoHash}-${torrent.jobId ?? "seeding"}`}>
                    <td>
                      <div className="file-name">{torrent.fileName || "unknown"}</div>
                      <small className="mono">{torrent.infoHash}</small>
                    </td>
                    <td>
                      <span className={clsx("pill", status.toLowerCase())}>{status}</span>
                    </td>
                    <td>{progress}%</td>
                    <td>
                      {torrent.completedPieces ?? "—"} / {torrent.totalPieces ?? "—"}
                    </td>
                    <td>{downloadSpeed}</td>
                    <td>{torrent.type}</td>
                    <td>
                      {torrent.jobId && (
                        <button className="ghost" onClick={() => onInspect(torrent.jobId)}>
                          Inspect
                        </button>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        ) : (
          <p className="feedback">No active torrents at the moment.</p>
        )}
      </div>
      {lastUpdated && <p className="timestamp">Last updated {new Date(lastUpdated).toLocaleTimeString()}</p>}
    </section>
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

export default ActiveTransfers;
