import './FormControls.css';

export default function ResponsePane({ title = 'Response', body }) {
  return (
    <div>
      <strong>{title}</strong>
      <pre className="response-pane">{body ?? 'No response yet.'}</pre>
    </div>
  );
}
