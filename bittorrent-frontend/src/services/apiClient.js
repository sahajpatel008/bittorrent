const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

const getUrl = (path) => `${API_BASE_URL}${path}`;

async function handleTextResponse(response) {
  const payload = await response.text();
  if (!response.ok) {
    throw new Error(payload || 'Request failed');
  }
  return payload;
}

async function handleBinaryResponse(response) {
  if (!response.ok) {
    throw new Error(await response.text());
  }

  const blob = await response.blob();
  const contentDisposition = response.headers.get('Content-Disposition') ?? '';
  const match = contentDisposition.match(/filename="?([^";]+)"?/i);
  const filename = match ? match[1] : 'download.bin';

  return { blob, filename };
}

export async function decodeBencode(encoded) {
  const url = getUrl(`/api/decode?encoded=${encodeURIComponent(encoded)}`);
  const response = await fetch(url);
  return handleTextResponse(response);
}

export async function fetchTorrentInfo(file) {
  const body = new FormData();
  body.append('file', file);
  const response = await fetch(getUrl('/api/info'), {
    method: 'POST',
    body
  });
  return handleTextResponse(response);
}

export async function handshakeWithPeer(peer, torrentPath) {
  const params = new URLSearchParams({ peer, file: torrentPath });
  const response = await fetch(getUrl(`/api/handshake?${params.toString()}`));
  return handleTextResponse(response);
}

export async function downloadPiece(file, pieceIndex) {
  const body = new FormData();
  body.append('file', file);
  const response = await fetch(getUrl(`/api/download/piece/${pieceIndex}`), {
    method: 'POST',
    body
  });
  return handleBinaryResponse(response);
}

export async function downloadFile(file) {
  const body = new FormData();
  body.append('file', file);
  const response = await fetch(getUrl('/api/download'), {
    method: 'POST',
    body
  });
  return handleBinaryResponse(response);
}

export async function fetchSeedingStatus(torrentPath) {
  const params = new URLSearchParams({ path: torrentPath });
  const response = await fetch(getUrl(`/api/debug/status?${params.toString()}`));
  return handleTextResponse(response);
}
