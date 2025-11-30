import { useState } from 'react';
import EndpointCard from '../components/EndpointCard';
import ResponsePane from '../components/ResponsePane';
import LoadingDots from '../components/LoadingDots';
import useApiRequest from '../hooks/useApiRequest';
import { fetchSeedingStatus } from '../services/apiClient';
import '../components/FormControls.css';
import './EndpointLayout.css';

const statusHighlights = [
  {
    title: 'Confirm server state',
    detail: 'Great for headless deployments: query which torrents the backend currently seeds.'
  },
  {
    title: 'Debug missing files',
    detail: 'See why a torrent path is missing or misconfigured without tailing logs.'
  },
  {
    title: 'Share snapshots',
    detail: 'Response pane returns plain JSON—copy/paste it into issue trackers for teammates.'
  }
];

export default function StatusPage() {
  const [path, setPath] = useState('/absolute/path/to/sample.torrent');
  const { execute, loading, error, data } = useApiRequest(fetchSeedingStatus);

  const handleSubmit = async (event) => {
    event.preventDefault();
    if (!path.trim()) {
      return;
    }
    await execute(path.trim());
  };

  return (
    <EndpointCard
      title="Seeding Status"
      description="Calls GET /api/debug/status?path=..."
    >
      <div className="endpoint-layout">
        <section className="endpoint-panel">
          <p className="helper-text">
            Keep tabs on which torrents are registered for seeding and whether the backend can read
            their metadata.
          </p>

          <ul className="endpoint-steps">
            {statusHighlights.map((item) => (
              <li key={item.title}>
                <strong>{item.title}</strong>
                <span>{item.detail}</span>
              </li>
            ))}
          </ul>
        </section>

        <section className="endpoint-panel">
          <form className="endpoint-form" onSubmit={handleSubmit}>
            <label className="form-field">
              <span>Torrent path on server</span>
              <input
                value={path}
                onChange={(e) => setPath(e.target.value)}
                placeholder="/var/torrents/sample.torrent"
              />
            </label>

            {error && <div className="alert">{error}</div>}

            <button type="submit" className="primary-btn" disabled={loading}>
              {loading ? <LoadingDots label="Checking" /> : 'Check Status'}
            </button>
          </form>

          <ResponsePane body={data} />
        </section>
      </div>
    </EndpointCard>
  );
}
