import { useState } from "react";

function AddPeerModal({ infoHash, onClose, onSubmit, pushAlert }) {
  const [ip, setIp] = useState("");
  const [port, setPort] = useState(6881);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  const validateIp = (ipAddress) => {
    // Basic IP validation (IPv4)
    const ipRegex = /^(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$/;
    return ipRegex.test(ipAddress);
  };

  const validatePort = (portNum) => {
    const num = Number(portNum);
    return Number.isInteger(num) && num >= 1 && num <= 65535;
  };

  const handleSubmit = async (event) => {
    event.preventDefault();
    setError(null);

    // Validate inputs
    if (!ip.trim()) {
      setError("IP address is required");
      return;
    }

    if (!validateIp(ip.trim())) {
      setError("Invalid IP address format");
      return;
    }

    if (!validatePort(port)) {
      setError("Port must be a number between 1 and 65535");
      return;
    }

    setLoading(true);
    try {
      await onSubmit(infoHash, ip.trim(), Number(port));
      onClose();
    } catch (err) {
      setError(err.message || "Failed to add peer");
    } finally {
      setLoading(false);
    }
  };

  const handleBackdropClick = (event) => {
    if (event.target === event.currentTarget) {
      onClose();
    }
  };

  return (
    <div className="modal-overlay" onClick={handleBackdropClick}>
      <div className="modal-content" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h3>Add Peer</h3>
          <button type="button" className="ghost" onClick={onClose}>
            ×
          </button>
        </div>
        <form className="stack" onSubmit={handleSubmit}>
          <div>
            <label>
              <span className="label">Info Hash</span>
              <input type="text" value={infoHash || ""} disabled className="mono" />
            </label>
          </div>
          <div>
            <label>
              <span className="label">Peer IP Address</span>
              <input
                type="text"
                value={ip}
                onChange={(e) => setIp(e.target.value)}
                placeholder="192.168.1.1"
                required
                disabled={loading}
              />
            </label>
          </div>
          <div>
            <label>
              <span className="label">Peer Port</span>
              <input
                type="number"
                value={port}
                onChange={(e) => setPort(e.target.value)}
                min="1"
                max="65535"
                required
                disabled={loading}
              />
            </label>
          </div>
          {error && <p className="feedback error">{error}</p>}
          <div className="modal-actions">
            <button type="button" className="ghost" onClick={onClose} disabled={loading}>
              Cancel
            </button>
            <button type="submit" className="primary" disabled={loading}>
              {loading ? "Adding..." : "Add Peer"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

export default AddPeerModal;

