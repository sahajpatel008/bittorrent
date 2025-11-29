import './LoadingDots.css';

export default function LoadingDots({ label = 'Loading' }) {
  return (
    <span className="loading-dots" aria-live="polite">
      {label}
      <span className="dot">.</span>
      <span className="dot">.</span>
      <span className="dot">.</span>
    </span>
  );
}
