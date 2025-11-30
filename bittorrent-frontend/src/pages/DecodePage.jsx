import { useState } from 'react';
import EndpointCard from '../components/EndpointCard';
import ResponsePane from '../components/ResponsePane';
import LoadingDots from '../components/LoadingDots';
import useApiRequest from '../hooks/useApiRequest';
import { decodeBencode } from '../services/apiClient';
import '../components/FormControls.css';
import './EndpointLayout.css';

const decodeSteps = [
  {
    title: 'Paste any raw payload',
    detail: 'Drop the literal bencoded bytes (dictionary, list, number, etc.) you received over the wire.'
  },
  {
    title: 'Let the API parse it',
    detail:
      'The backend returns formatted JSON so you can compare against what popular torrent clients expect.'
  },
  {
    title: 'Inspect nested data',
    detail: 'Identify tracker lists, peer IDs, or corrupted nodes before sending new requests.'
  }
];

export default function DecodePage() {
  const [encoded, setEncoded] = useState('d3:bar4:spam3:fooi42ee');
  const { execute, loading, error, data } = useApiRequest(decodeBencode);

  const handleSubmit = async (event) => {
    event.preventDefault();
    if (!encoded.trim()) {
      return;
    }
    await execute(encoded.trim());
  };

  return (
    <EndpointCard
      title="Decode Bencoded Payload"
      description="Calls GET /api/decode?encoded=... and renders JSON."
    >
      <div className="endpoint-layout">
        <section className="endpoint-panel">
          <p className="helper-text">
            Simplify debugging by turning opaque wire bytes back into readable JSON objects.
          </p>

          <ul className="endpoint-steps">
            {decodeSteps.map((step) => (
              <li key={step.title}>
                <strong>{step.title}</strong>
                <span>{step.detail}</span>
              </li>
            ))}
          </ul>

          <div className="endpoint-sample">
            <strong>Sample payload</strong>
            <code>d3:bar4:spam3:fooi42ee</code>
          </div>
        </section>

        <section className="endpoint-panel">
          <form className="endpoint-form" onSubmit={handleSubmit}>
            <label className="form-field">
              <span>Bencoded input</span>
              <textarea
                value={encoded}
                onChange={(e) => setEncoded(e.target.value)}
                placeholder="d3:bar4:spam3:fooi42ee"
              />
            </label>

            {error && <div className="alert">{error}</div>}

            <button type="submit" className="primary-btn" disabled={loading}>
              {loading ? <LoadingDots label="Decoding" /> : 'Decode Payload'}
            </button>
          </form>

          <ResponsePane body={data} />
        </section>
      </div>
    </EndpointCard>
  );
}
