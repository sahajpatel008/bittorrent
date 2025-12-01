import { useState } from "react";
import { postForm } from "../api.js";

function TorrentAnalyzer({ onInfoReady, pushAlert }) {
  const [metadata, setMetadata] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  const handleSubmit = async (event) => {
    event.preventDefault();
    const files = event.target.elements.torrent.files;
    if (!files?.length) return;
    setBusy(true);
    setError(null);
    const formData = new FormData();
    formData.append("file", files[0]);
    try {
      const info = await postForm("/torrents/info", formData);
      setMetadata(info);
      onInfoReady?.(info);
      pushAlert?.("Torrent metadata loaded.", "success");
    } catch (err) {
      setError(err.message);
      pushAlert?.("Failed to parse torrent.", "error");
    } finally {
      setBusy(false);
      event.target.reset();
    }
  };

  return (
    <section className="panel">
      <header>
        <h2>Torrent Analyzer</h2>
        <p>Inspect announce URLs, info hash, and piece stats before downloading.</p>
      </header>
      <form onSubmit={handleSubmit} className="stack">
        <label className="file-picker">
          <input name="torrent" type="file" accept=".torrent" required />
          <span>Drop or select a .torrent file</span>
        </label>
        <button type="submit" className="primary" disabled={busy}>
          {busy ? "Analyzing..." : "Analyze"}
        </button>
      </form>
      {error && <p className="feedback error">{error}</p>}
      {metadata && (
        <dl className="metrics-grid">
          <div>
            <dt>Name</dt>
            <dd>{metadata.name || "Unknown"}</dd>
          </div>
          <div>
            <dt>Tracker</dt>
            <dd>{metadata.trackerUrl || metadata.announce || "n/a"}</dd>
          </div>
          <div>
            <dt>Info Hash</dt>
            <dd className="mono">{metadata.infoHash}</dd>
          </div>
          <div>
            <dt>Length</dt>
            <dd>{formatBytes(metadata.length)}</dd>
          </div>
          <div>
            <dt>Piece Length</dt>
            <dd>{formatBytes(metadata.pieceLength)}</dd>
          </div>
          <div>
            <dt>Pieces</dt>
            <dd>{metadata.pieceCount}</dd>
          </div>
        </dl>
      )}
    </section>
  );
}

function formatBytes(bytes) {
  if (!Number.isFinite(bytes)) return "-";
  if (bytes === 0) return "0 B";
  const units = ["B", "KB", "MB", "GB", "TB"];
  const index = Math.floor(Math.log(bytes) / Math.log(1024));
  const value = bytes / Math.pow(1024, index);
  return `${value.toFixed(1)} ${units[index]}`;
}

export default TorrentAnalyzer;
