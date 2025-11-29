import { Link } from 'react-router-dom';
import EndpointCard from '../components/EndpointCard';
import './Pages.css';

const endpoints = [
  {
    title: 'Decode Bencode',
    description: 'Send raw bencoded strings to /api/decode and view JSON output.'
  },
  {
    title: 'Torrent Metadata',
    description: 'Upload a .torrent file to inspect tracker URL, info hash, and piece hashes.'
  },
  {
    title: 'Peer Handshake',
    description: 'Provide a peer address and server-side torrent path to see the remote peer ID.'
  },
  {
    title: 'Download Piece',
    description: 'Fetch a single piece as binary data and save it locally.'
  },
  {
    title: 'Download File',
    description: 'Stream the complete payload for the torrent and save it as a file.'
  },
  {
    title: 'Seeding Status',
    description: 'Check which torrents are registered for seeding on the backend.'
  }
];

const quickActions = [
  {
    to: '/decode',
    title: 'Decode payload',
    detail: 'Troubleshoot tracker announcements by decoding raw bencode strings.'
  },
  {
    to: '/info',
    title: 'Inspect torrent',
    detail: 'Preview announce URLs, info hash, and files before seeding.'
  },
  {
    to: '/download-piece',
    title: 'Fetch piece',
    detail: 'Download a single piece index to validate corrupted chunks.'
  }
];

const heroStats = [
  { label: 'Default API', value: 'http://localhost:8080' },
  { label: 'Backend', value: 'Spring Boot' },
  { label: 'Transport', value: 'REST + JSON' }
];

const heroHighlights = [
  'Upload .torrent files',
  'Call debugging endpoints',
  'Inspect raw responses'
];

export default function HomePage() {
  return (
    <div className="home-grid">
      <EndpointCard
        title="Getting Started"
        description="Point this UI at your running Spring Boot service."
      >
        <div className="home-hero">
          <p className="helper-text">
          </p>

          <div className="environment-grid">
            {heroStats.map((stat) => (
              <div key={stat.label}>
                <span>{stat.label}</span>
                <strong>{stat.value}</strong>
              </div>
            ))}
          </div>

          <div className="badge-list">
            {heroHighlights.map((highlight) => (
              <span key={highlight}>{highlight}</span>
            ))}
          </div>
        </div>
      </EndpointCard>

      <div className="home-columns">
        <EndpointCard title="Available Operations">
          <ul className="endpoint-list">
            {endpoints.map((endpoint) => (
              <li key={endpoint.title}>
                <strong>{endpoint.title}</strong>
                <span>{endpoint.description}</span>
              </li>
            ))}
          </ul>
        </EndpointCard>

        <EndpointCard title="Quick actions" description="Jump directly into the tooling you need.">
          <div className="quick-action-grid">
            {quickActions.map((action) => (
              <Link key={action.to} to={action.to} className="quick-action-card">
                <div>
                  <strong>{action.title}</strong>
                  <span>{action.detail}</span>
                </div>
                <span className="quick-action-pill">Open</span>
              </Link>
            ))}
          </div>
        </EndpointCard>
      </div>
    </div>
  );
}
