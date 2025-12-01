import { useState } from "react";
import { postForm } from "../api.js";

function DownloadManager({ onJobCreated, pushAlert }) {
  const [status, setStatus] = useState(null);
  const [busy, setBusy] = useState(false);

  const handleSubmit = async (event) => {
    event.preventDefault();
    const { file, output } = event.target.elements;
    if (!file.files?.length) return;
    setBusy(true);
    setStatus("Starting download...");
    const formData = new FormData();
    formData.append("file", file.files[0]);
    if (output.value.trim()) {
      formData.append("outputFileName", output.value.trim());
    }
    try {
      const result = await postForm("/torrents/download", formData);
      setStatus(`Job ${result.jobId} started`);
      onJobCreated?.(result.jobId);
      pushAlert?.("Download job started.", "success");
    } catch (err) {
      setStatus(err.message);
      pushAlert?.("Failed to start download.", "error");
    } finally {
      setBusy(false);
      event.target.reset();
    }
  };

  return (
    <section className="panel">
      <header>
        <h2>Download Manager</h2>
        <p>Upload a torrent to start an asynchronous download job.</p>
      </header>
      <form className="stack" onSubmit={handleSubmit}>
        <label>
          <span className="label">Torrent file</span>
          <input type="file" name="file" accept=".torrent" required />
        </label>
        <label>
          <span className="label">Output filename (optional)</span>
          <input type="text" name="output" placeholder="Defaults to torrent name" />
        </label>
        <button className="primary" type="submit" disabled={busy}>
          {busy ? "Starting..." : "Start Download"}
        </button>
      </form>
      {status && <p className="feedback">{status}</p>}
    </section>
  );
}

export default DownloadManager;
