import { useState } from 'react';
import EndpointCard from '../components/EndpointCard';
import FileUploadField from '../components/FileUploadField';
import ResponsePane from '../components/ResponsePane';
import LoadingDots from '../components/LoadingDots';
import useApiRequest from '../hooks/useApiRequest';
import { downloadPiece } from '../services/apiClient';
import { triggerBrowserDownload } from '../services/fileSaver';
import '../components/FormControls.css';
import './EndpointLayout.css';

const pieceSteps = [
  {
    title: 'Upload torrent metadata',
    detail: 'We read piece length and hashes to know which chunk to request.'
  },
  {
    title: 'Pick a piece index',
    detail: 'Zero-based index. Use tracker debug logs or prior responses to know which piece failed.'
  },
  {
    title: 'Save binary response',
    detail: 'We stream the raw bytes back so you can re-hash or diff locally.'
  }
];

export default function DownloadPiecePage() {
  const [file, setFile] = useState(null);
  const [pieceIndex, setPieceIndex] = useState(0);
  const { execute, loading, error, data, setData } = useApiRequest(downloadPiece);

  const handleSubmit = async (event) => {
    event.preventDefault();
    if (!file) {
      alert('Upload a torrent first');
      return;
    }

    try {
      const { blob, filename } = await execute(file, Number(pieceIndex));
      triggerBrowserDownload(blob, filename);
      setData(`Piece downloaded as ${filename}`);
    } catch (err) {
      // error state already handled
    }
  };

  return (
    <EndpointCard
      title="Download Specific Piece"
      description="Calls POST /api/download/piece/{index} and streams the binary response"
    >
      <div className="endpoint-layout">
        <section className="endpoint-panel">
          <p className="helper-text">
            Perfect for reproducing corrupt chunks and validating piece hashes outside of the
            BitTorrent swarm.
          </p>

          <ul className="endpoint-steps">
            {pieceSteps.map((step) => (
              <li key={step.title}>
                <strong>{step.title}</strong>
                <span>{step.detail}</span>
              </li>
            ))}
          </ul>

          <div className="badge-list">
            <span>Hash mismatch debug</span>
            <span>Selective sync</span>
            <span>Streaming preview</span>
          </div>
        </section>

        <section className="endpoint-panel">
          <form className="endpoint-form" onSubmit={handleSubmit}>
            <FileUploadField onChange={setFile} />
            {file && <span className="helper-text">Selected: {file.name}</span>}

            <label className="form-field">
              <span>Piece index</span>
              <input
                type="number"
                min="0"
                value={pieceIndex}
                onChange={(e) => setPieceIndex(e.target.value)}
              />
            </label>

            {error && <div className="alert">{error}</div>}

            <button type="submit" className="primary-btn" disabled={loading}>
              {loading ? <LoadingDots label="Downloading" /> : 'Download Piece'}
            </button>
          </form>

          <ResponsePane body={data} />
        </section>
      </div>
    </EndpointCard>
  );
}
