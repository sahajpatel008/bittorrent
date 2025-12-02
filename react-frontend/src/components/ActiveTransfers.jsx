import clsx from "clsx";

function ActiveTransfers({
  torrents,
  loading,
  lastUpdated,
  autoRefresh,
  onToggleAuto,
  onRefresh,
  onInspect,
  onDelete,
  onForceAnnounce,
  onAddPeer,
  pushAlert
}) {
  const handleFolderClick = (downloadPath, event) => {
    event.preventDefault();
    event.stopPropagation();
    
    // Extract directory path (remove filename if it's a file path)
    let folderPath = downloadPath;
    // Check if it's a file path (contains a filename with extension)
    const lastSlash = folderPath.lastIndexOf('/');
    if (lastSlash > 0) {
      // Check if the part after last slash looks like a filename (has extension)
      const afterSlash = folderPath.substring(lastSlash + 1);
      if (afterSlash.includes('.')) {
        // It's a file path, get the directory
        folderPath = folderPath.substring(0, lastSlash);
      }
    }
    
    // Try to open folder using file:// URL (works on some systems)
    try {
      // Convert path to file:// URL
      // On Windows, we need to handle backslashes
      const normalizedPath = folderPath.replace(/\\/g, '/');
      let fileUrl;
      if (normalizedPath.startsWith('/')) {
        // Unix/Mac path
        fileUrl = 'file://' + normalizedPath;
      } else if (normalizedPath.match(/^[A-Za-z]:/)) {
        // Windows path like C:/path
        fileUrl = 'file:///' + normalizedPath;
      } else {
        // Relative path or other
        fileUrl = 'file://' + normalizedPath;
      }
      
      // Try to open in new window/tab (may not work due to browser security)
      window.open(fileUrl, '_blank');
    } catch (e) {
      // Fallback: copy to clipboard
    }
    
    // Always copy path to clipboard as fallback
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(folderPath).then(() => {
        if (pushAlert) {
          pushAlert(`Folder path copied to clipboard: ${folderPath}`, "success");
        } else {
          alert(`Folder path copied to clipboard:\n${folderPath}`);
        }
      }).catch(() => {
        // Fallback if clipboard API fails
        if (pushAlert) {
          pushAlert(`Folder path: ${folderPath}`, "info");
        } else {
          alert(`Folder path: ${folderPath}`);
        }
      });
    } else {
      // Fallback if clipboard API not available
      if (pushAlert) {
        pushAlert(`Folder path: ${folderPath}`, "info");
      } else {
        alert(`Folder path: ${folderPath}`);
      }
    }
  };
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
                      <div className="file-name">
                        {torrent.downloadPath && (
                          <span 
                            className="folder-icon" 
                            title={`Click to open folder: ${torrent.downloadPath}`}
                            onClick={(e) => handleFolderClick(torrent.downloadPath, e)}
                          >
                            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                              <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"></path>
                            </svg>
                          </span>
                        )}
                        {torrent.fileName || "unknown"}
                      </div>
                      <small className="mono">{torrent.infoHash}</small>
                      {torrent.downloadPath && (
                        <small className="download-path" title={torrent.downloadPath}>
                          {torrent.downloadPath}
                        </small>
                      )}
                    </td>
                    <td>
                      <span className={clsx("pill", status.toLowerCase())}>{status}</span>
                      {torrent.fileExists === false && (
                        <div className="file-missing-warning" title={torrent.error || "File not found"}>
                          ⚠️ File missing
                        </div>
                      )}
                    </td>
                    <td>{progress}%</td>
                    <td>
                      {torrent.completedPieces ?? "—"} / {torrent.totalPieces ?? "—"}
                    </td>
                    <td>{downloadSpeed}</td>
                    <td>{torrent.type}</td>
                    <td className="row-actions">
                      {torrent.jobId && (
                        <button type="button" className="ghost" onClick={() => onInspect(torrent.jobId)}>
                          Inspect
                        </button>
                      )}
                      {onForceAnnounce && (
                        <button
                          type="button"
                          className="ghost"
                          onClick={() => onForceAnnounce?.(torrent.infoHash)}
                          disabled={!torrent.infoHash}
                        >
                          Force Announce
                        </button>
                      )}
                      {onAddPeer && (
                        <button
                          type="button"
                          className="ghost"
                          onClick={() => onAddPeer?.(torrent.infoHash)}
                          disabled={!torrent.infoHash}
                        >
                          Add Peer
                        </button>
                      )}
                      <button
                        type="button"
                        className="ghost"
                        onClick={() => onDelete?.(torrent.infoHash)}
                      >
                        Remove
                      </button>
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
