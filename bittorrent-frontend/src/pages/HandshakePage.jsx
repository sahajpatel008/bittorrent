import { useState } from 'react';
import EndpointCard from '../components/EndpointCard';
import ResponsePane from '../components/ResponsePane';
import LoadingDots from '../components/LoadingDots';
import useApiRequest from '../hooks/useApiRequest';
import { handshakeWithPeer } from '../services/apiClient';
import '../components/FormControls.css';
import './EndpointLayout.css';

const handshakeSteps = [
  {
    title: 'Pick a remote peer',
    detail: 'Use ip:port notation. Public peers often listen on 6881-6889.'
  },
  {
    title: 'Reference the torrent',
    detail: 'Provide the absolute path where the backend can read the .torrent file.'
  },
  {
    title: 'Inspect response',
    detail: 'Verify peer ID, supported extensions, and whether the handshake succeeded.'
  }
];

const handshakeStats = [
  { label: 'Handshake bytes', value: '68 bytes' },
  { label: 'Peer ID length', value: '20 bytes' },
  { label: 'Default timeout', value: '5s' }
];

export default function HandshakePage() {
  const [peer, setPeer] = useState('127.0.0.1:6881');
  const [path, setPath] = useState('/absolute/path/to/sample.torrent');
  const { execute, loading, error, data } = useApiRequest(handshakeWithPeer);

  const handleSubmit = async (event) => {
    event.preventDefault();
    if (!peer.trim() || !path.trim()) {
      return;
    }
    await execute(peer.trim(), path.trim());
  };

  return (
    <EndpointCard
      title="Peer Handshake"
      description="Calls GET /api/handshake?peer=ip:port&file=/path/to/torrent"
    >
      <div className="endpoint-layout">
        <section className="endpoint-panel">
          <p className="helper-text">
            Quickly test peer reachability and confirm that your BitTorrent handshake flow works
            outside of your JVM logs.
          </p>

          <ul className="endpoint-steps">
            {handshakeSteps.map((step) => (
              <li key={step.title}>
                <strong>{step.title}</strong>
                <span>{step.detail}</span>
              </li>
            ))}
          </ul>

          <div className="inline-metrics">
            {handshakeStats.map((stat) => (
              <div key={stat.label}>
                <span>{stat.label}</span>
                <strong>{stat.value}</strong>
              </div>
            ))}
          </div>
        </section>

        <section className="endpoint-panel">
          <div className="endpoint-callout">
            The torrent file path must be absolute and readable from the server hosting the API.
          </div>

          <form className="endpoint-form" onSubmit={handleSubmit}>
            <label className="form-field">
              <span>Peer address (ip:port)</span>
              <input
                value={peer}
                onChange={(e) => setPeer(e.target.value)}
                placeholder="93.184.216.34:6881"
              />
            </label>

            <label className="form-field">
              <span>Server-side torrent path</span>
              <input
                value={path}
                onChange={(e) => setPath(e.target.value)}
                placeholder="/var/torrents/debian.torrent"
              />
            </label>

            {error && <div className="alert">{error}</div>}

            <button type="submit" className="primary-btn" disabled={loading}>
              {loading ? <LoadingDots label="Handshaking" /> : 'Run Handshake'}
            </button>
          </form>

          <ResponsePane body={data} />
        </section>
      </div>
    </EndpointCard>
  );
}
