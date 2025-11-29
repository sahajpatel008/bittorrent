import { NavLink } from 'react-router-dom';
import './AppLayout.css';

const links = [
  { to: '/', label: 'Dashboard' },
  { to: '/decode', label: 'Decode' },
  { to: '/info', label: 'Torrent Info' },
  { to: '/handshake', label: 'Handshake' },
  { to: '/download-piece', label: 'Download Piece' },
  { to: '/download', label: 'Download File' },
  { to: '/status', label: 'Seeding Status' }
];

export default function AppLayout({ children }) {
  return (
    <div className="app-shell">
      <header className="app-header">
        <div>
          <h1>BitTorrent Client UI</h1>
          <p>Interact with your Spring Boot BitTorrent API</p>
        </div>
        <span className="badge">API</span>
      </header>

      <nav className="app-nav">
        {links.map((link) => (
          <NavLink
            key={link.to}
            to={link.to}
            className={({ isActive }) =>
              `nav-link ${isActive ? 'active' : ''}`
            }
            end={link.to === '/'}
          >
            {link.label}
          </NavLink>
        ))}
      </nav>

      <main className="app-main">{children}</main>
    </div>
  );
}
