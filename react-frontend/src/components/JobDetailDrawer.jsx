import { useMemo } from "react";

function JobDetailDrawer({ jobId, snapshot, onClose, onViewFullDetails }) {
  const job = useMemo(() => snapshot || null, [snapshot]);

  if (!jobId) {
    return null;
  }

  const handleViewFullDetails = () => {
    onViewFullDetails?.();
    // Don't close - just navigate, the drawer will hide because activeView changes
  };

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
      {!job && <p>Loading job details...</p>}
      {job && (
        <>
          <div className="progress">
            <div className="progress-track">
              <div className="progress-bar" style={{ width: `${Number(job.progress ?? 0).toFixed(1)}%` }} />
            </div>
            <small>{Number(job.progress ?? 0).toFixed(1)}% complete</small>
          </div>
          <dl className="metrics-grid small">
            {[
              ["Status", job.status],
              ["Pieces", `${job.completedPieces ?? 0} / ${job.totalPieces ?? 0}`],
              ["Speed", renderSpeed(job.overallDownloadSpeed)],
              ["Started", formatDate(job.startTime)]
            ].map(([label, value]) => (
              <div key={label}>
                <dt>{label}</dt>
                <dd>{value || "—"}</dd>
              </div>
            ))}
          </dl>
          
          <button className="primary full-width" onClick={handleViewFullDetails}>
            View Full Details
          </button>
          
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
