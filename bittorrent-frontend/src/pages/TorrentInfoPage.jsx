import { useState } from 'react';
import EndpointCard from '../components/EndpointCard';
import ResponsePane from '../components/ResponsePane';
import LoadingDots from '../components/LoadingDots';
import useApiRequest from '../hooks/useApiRequest';
import { fetchTorrentInfo } from '../services/apiClient';
import '../components/FormControls.css';
import './TorrentInfoPage.css';

const metadataHighlights = [
  {
    title: 'Trackers',
    body: 'Inspect announce URLs and tiered tracker lists embedded in the torrent.'
  },
  {
    title: 'Pieces',
    body: 'Verify piece length and hashed segments to debug download mismatches.'
  },
  {
    title: 'Files',
    body: 'Understand single vs. multi-file payloads without cracking open the torrent.'
  }
];

const formatBytes = (size) => {
  if (!Number.isFinite(size) || size <= 0) {
    return '0 B';
  }
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  const exponent = Math.min(
    Math.floor(Math.log(size) / Math.log(1024)),
    units.length - 1
  );
  const value = size / Math.pow(1024, exponent);
  const fractionDigits = value >= 10 || exponent === 0 ? 0 : 1;
  return `${value.toFixed(fractionDigits)} ${units[exponent]}`;
};

const formatDate = (timestamp) => {
  if (!timestamp) {
    return '';
  }
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short'
  }).format(new Date(timestamp));
};

export default function TorrentInfoPage() {
  const [file, setFile] = useState(null);
  const { execute, loading, error, data } = useApiRequest(fetchTorrentInfo);

  const handleSubmit = async (event) => {
    event.preventDefault();
    if (!file) {
      alert('Please choose a .torrent file');
      return;
    }
    await execute(file);
  };

  return (
    <EndpointCard
      title="Torrent Metadata"
      description="Upload a torrent to call POST /api/info"
    >
      <div className="torrent-info-layout">
        <section className="torrent-info-panel">
          <p className="helper-text">
            Drop any <code>.torrent</code> file to quickly preview the metadata your backend
            exposes. We&apos;ll call <code>POST /api/info</code> and echo the JSON below.
          </p>

          <ul className="metadata-highlights">
            {metadataHighlights.map((item) => (
              <li key={item.title}>
                <span>{item.title}</span>
                <p>{item.body}</p>
              </li>
            ))}
          </ul>
        </section>

        <section className="torrent-info-panel">
          <form className="torrent-info-form" onSubmit={handleSubmit}>
            <label className={`torrent-dropzone ${file ? 'has-file' : ''}`}>
              <input
                type="file"
                accept=".torrent"
                onChange={(event) => setFile(event.target.files?.[0] ?? null)}
              />
              <div className="dropzone-content">
                <svg
                  width="32"
                  height="32"
                  viewBox="0 0 24 24"
                  role="img"
                  aria-hidden="true"
                >
                  <path
                    d="M12 16V4m0 0-3 3m3-3 3 3M4 14v5h16v-5"
                    stroke="currentColor"
                    strokeWidth="1.5"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    fill="none"
                  />
                </svg>
                <div>
                  <strong>Drag & drop a torrent</strong>
                  <span>or click to browse your files</span>
                </div>
              </div>
            </label>

            <div className="file-summary">
              {file ? (
                <>
                  <div className="file-summary-header">
                    <div>
                      <span>Ready to upload</span>
                      <strong>{file.name}</strong>
                    </div>
                    <button
                      type="button"
                      className="clear-btn"
                      onClick={() => setFile(null)}
                    >
                      Clear
                    </button>
                  </div>
                  <dl>
                    <div>
                      <dt>Size</dt>
                      <dd>{formatBytes(file.size)}</dd>
                    </div>
                    <div>
                      <dt>Modified</dt>
                      <dd>{formatDate(file.lastModified)}</dd>
                    </div>
                  </dl>
                </>
              ) : (
                <p>No file selected yet.</p>
              )}
            </div>

            {error && <div className="alert">{error}</div>}

            <button type="submit" className="primary-btn" disabled={loading}>
              {loading ? <LoadingDots label="Fetching" /> : 'Fetch Metadata'}
            </button>
          </form>

          <ResponsePane body={data} />
        </section>
      </div>
    </EndpointCard>
  );
}
