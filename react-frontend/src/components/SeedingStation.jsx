import { useState } from "react";
import { postForm } from "../api.js";

function SeedingStation({ pushAlert, onSeeding }) {
  const [status, setStatus] = useState(null);
  const [busy, setBusy] = useState(false);

  const handleSubmit = async (event) => {
    event.preventDefault();
    const { torrent, payload } = event.target.elements;
    if (!torrent.files?.length || !payload.files?.length) return;
    setBusy(true);
    setStatus("Bootstrapping seeding session...");
    const formData = new FormData();
    formData.append("torrent", torrent.files[0]);
    formData.append("file", payload.files[0]);
    try {
      const response = await postForm("/torrents/seed", formData);
      setStatus(`Now seeding ${response.infoHash}`);
      onSeeding?.(response.infoHash);
      pushAlert?.("Seeding started.", "success");
      event.target.reset();
    } catch (err) {
      setStatus(err.message);
      pushAlert?.("Failed to seed torrent.", "error");
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="panel">
      <header>
        <h2>Seeding Station</h2>
        <p>Attach a local payload to existing torrent metadata and announce yourself.</p>
      </header>
      <form className="stack" onSubmit={handleSubmit}>
        <label>
          <span className="label">Torrent file</span>
          <input type="file" name="torrent" accept=".torrent" required />
        </label>
        <label>
          <span className="label">Payload</span>
          <input type="file" name="payload" required />
        </label>
        <button className="primary" type="submit" disabled={busy}>
          {busy ? "Starting..." : "Start Seeding"}
        </button>
      </form>
      {status && <p className="feedback">{status}</p>}
    </section>
  );
}

export default SeedingStation;
