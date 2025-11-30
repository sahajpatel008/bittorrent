import { Routes, Route, Navigate } from 'react-router-dom';
import AppLayout from './components/AppLayout';
import HomePage from './pages/HomePage';
import DecodePage from './pages/DecodePage';
import TorrentInfoPage from './pages/TorrentInfoPage';
import HandshakePage from './pages/HandshakePage';
import DownloadPiecePage from './pages/DownloadPiecePage';
import DownloadFilePage from './pages/DownloadFilePage';
import StatusPage from './pages/StatusPage';

export default function App() {
  return (
    <AppLayout>
      <Routes>
        <Route path="/" element={<HomePage />} />
        <Route path="/decode" element={<DecodePage />} />
        <Route path="/info" element={<TorrentInfoPage />} />
        <Route path="/handshake" element={<HandshakePage />} />
        <Route path="/download-piece" element={<DownloadPiecePage />} />
        <Route path="/download" element={<DownloadFilePage />} />
        <Route path="/status" element={<StatusPage />} />
        <Route path="*" element={<Navigate to="/" />} />
      </Routes>
    </AppLayout>
  );
}
