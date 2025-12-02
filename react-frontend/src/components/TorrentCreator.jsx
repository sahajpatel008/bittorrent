import { useState } from "react";
import { API_BASE } from "../api.js";

function TorrentCreator({ pushAlert }) {
  const [status, setStatus] = useState(null);
  const [busy, setBusy] = useState(false);

  const handleSubmit = async (event) => {
    event.preventDefault();
    const { payload, name } = event.target.elements;
    if (!payload.files?.length) {
      return;
    }
    const file = payload.files[0];
    const customName = name.value.trim();
    const downloadName = ensureTorrentName(customName || deriveBaseName(file.name));

    setBusy(true);
    setStatus("Creating torrent...");

    const formData = new FormData();
    formData.append("file", file);
    if (customName) {
      formData.append("outputName", customName);
    }

    try {
      const response = await fetch(`${API_BASE}/torrents/create`, {
        method: "POST",
        body: formData
      });
      if (!response.ok) {
        const text = await response.text();
        throw new Error(parseError(text, response.status));
      }
      const blob = await response.blob();
      triggerDownload(blob, downloadName);
      setStatus(`Created ${downloadName}`);
      pushAlert?.("Torrent file generated.", "success");
    } catch (err) {
      setStatus(err.message);
      pushAlert?.("Failed to create torrent.", "error");
    } finally {
      setBusy(false);
      event.target.reset();
    }
  };

  return (
    <section className="panel">
      <header>
        <h2>Torrent Builder</h2>
        <p>Upload a payload and receive a tracker-ready .torrent pointing at your local tracker.</p>
      </header>
      <form className="stack" onSubmit={handleSubmit}>
        <label>
          <span className="label">Payload</span>
          <input type="file" name="payload" required />
        </label>
        <label>
          <span className="label">Output name (optional)</span>
          <input type="text" name="name" placeholder="Defaults to payload name" />
        </label>
        <button className="primary" type="submit" disabled={busy}>
          {busy ? "Creating..." : "Generate Torrent"}
        </button>
      </form>
      {status && <p className="feedback">{status}</p>}
    </section>
  );
}

function triggerDownload(blob, name) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = name;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(url);
}

function deriveBaseName(original) {
  if (!original) return "new-torrent";
  const dot = original.lastIndexOf(".");
  return dot > 0 ? original.slice(0, dot) : original;
}

function ensureTorrentName(name) {
  return name.endsWith(".torrent") ? name : `${name}.torrent`;
}

function parseError(payload, status) {
  try {
    const data = JSON.parse(payload);
    return data.error ?? JSON.stringify(data);
  } catch {
    return payload || `Request failed (${status})`;
  }
}

export default TorrentCreator;
