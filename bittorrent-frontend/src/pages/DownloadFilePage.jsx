import { useState } from 'react';
import EndpointCard from '../components/EndpointCard';
import FileUploadField from '../components/FileUploadField';
import ResponsePane from '../components/ResponsePane';
import LoadingDots from '../components/LoadingDots';
import useApiRequest from '../hooks/useApiRequest';
import { downloadFile } from '../services/apiClient';
import { triggerBrowserDownload } from '../services/fileSaver';
import '../components/FormControls.css';
import './EndpointLayout.css';

const downloadSteps = [
  {
    title: 'Upload torrent metadata',
    detail: 'We only need the .torrent to understand piece layout and hash tree.'
  },
  {
    title: 'Stream to browser',
    detail: 'The backend pipes the assembled payload; we trigger a download automatically.'
  },
  {
    title: 'Validate checksum',
    detail: 'Use the Response panel to confirm returned filename, size, and any warnings.'
  }
];

export default function DownloadFilePage() {
  const [file, setFile] = useState(null);
  const { execute, loading, error, data, setData } = useApiRequest(downloadFile);

  const handleSubmit = async (event) => {
    event.preventDefault();
    if (!file) {
      alert('Upload a torrent first');
      return;
    }

    try {
      const { blob, filename } = await execute(file);
      triggerBrowserDownload(blob, filename);
      setData(`Full file downloaded as ${filename}`);
    } catch {
      // handled via hook
    }
  };

  return (
    <EndpointCard
      title="Download Complete File"
      description="Calls POST /api/download and saves the final payload"
    >
      <div className="endpoint-layout">
        <section className="endpoint-panel">
          <p className="helper-text">
            Quickly validate that end-to-end download logic in your Spring Boot service returns the
            payload you expect.
          </p>

          <ul className="endpoint-steps">
            {downloadSteps.map((step) => (
              <li key={step.title}>
                <strong>{step.title}</strong>
                <span>{step.detail}</span>
              </li>
            ))}
          </ul>

          <div className="inline-metrics">
            <div>
              <span>Content-Type</span>
              <strong>application/octet-stream</strong>
            </div>
            <div>
              <span>Trigger</span>
              <strong>browser download</strong>
            </div>
          </div>
        </section>

        <section className="endpoint-panel">
          <form className="endpoint-form" onSubmit={handleSubmit}>
            <FileUploadField onChange={setFile} />
            {file && <span className="helper-text">Selected: {file.name}</span>}

            {error && <div className="alert">{error}</div>}

            <button type="submit" className="primary-btn" disabled={loading}>
              {loading ? <LoadingDots label="Downloading" /> : 'Download File'}
            </button>
          </form>

          <ResponsePane body={data} />
        </section>
      </div>
    </EndpointCard>
  );
}
