# BitTorrent REST API Endpoints

## Overview
This document describes the REST API endpoints for the BitTorrent client application. All endpoints are prefixed with `/api`.

## Base URL
```
http://localhost:8080/api
```

---

## Endpoints

### 1. Health Check
**GET** `/` or `/api/`

Returns API status.

**Response:**
```json
{
  "status": "running",
  "message": "BitTorrent API is running"
}
```

---

### 2. Get Torrent Information
**POST** `/api/torrents/info`

Parses a `.torrent` file and returns metadata as JSON.

**Request:**
- Content-Type: `multipart/form-data`
- Form field: `file` (torrent file)

**Response:**
```json
{
  "trackerUrl": "http://tracker.example.com/announce",
  "name": "example_file.txt",
  "length": 12345,
  "pieceLength": 16384,
  "pieceCount": 1,
  "infoHash": "5c03506aa73be7824eac651d0980bcdb912cfa81"
}
```

---

### 3. Download Piece
**POST** `/api/torrents/download/piece/{pieceIndex}`

Downloads a specific piece from a torrent.

**Path Parameters:**
- `pieceIndex` (int) - Index of the piece to download (0-based)

**Request:**
- Content-Type: `multipart/form-data`
- Form field: `file` (torrent file)

**Response:**
- Content-Type: `application/octet-stream`
- Binary data of the piece

---

### 4. Start Asynchronous Download
**POST** `/api/torrents/download`

Starts an asynchronous download job. Returns immediately with a job ID. The download processes in the background.

**Request:**
- Content-Type: `multipart/form-data`
- Form fields:
  - `file` (file) - The torrent file (required)
  - `outputFileName` (string) - Optional custom filename for the downloaded file

**Response:**
- Status Code: `202 Accepted`
- Body:
```json
{
  "jobId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "started",
  "message": "Download started. Use /api/torrents/download/{jobId}/status to check progress."
}
```

**Note:** Downloads are saved to `~/bittorrent-downloads/` directory. Files persist across server restarts.

---

### 5. Get Download Job Status
**GET** `/api/torrents/download/{jobId}/status`

Gets the current status and progress of a download job.

**Path Parameters:**
- `jobId` (string) - The job ID returned from starting a download

**Response:**
```json
{
  "jobId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "downloading",
  "fileName": "example_file.txt",
  "infoHash": "5c03506aa73be7824eac651d0980bcdb912cfa81",
  "totalPieces": 100,
  "completedPieces": 45,
  "progress": 45.0
}
```

**Status Values:**
- `pending` - Job created but not started
- `downloading` - Download in progress
- `completed` - Download finished successfully
- `failed` - Download failed (check `error` field)
- `cancelled` - Download was cancelled

**When Status is `completed`:**
```json
{
  "jobId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "completed",
  "fileName": "example_file.txt",
  "infoHash": "5c03506aa73be7824eac651d0980bcdb912cfa81",
  "totalPieces": 100,
  "completedPieces": 100,
  "progress": 100.0,
  "filePath": "/Users/username/bittorrent-downloads/example_file.txt",
  "fileSize": 12345678
}
```

---

### 6. Download Completed File
**GET** `/api/torrents/download/{jobId}/file`

Downloads the completed file from a finished download job.

**Path Parameters:**
- `jobId` (string) - The job ID of a completed download

**Response:**
- Content-Type: `application/octet-stream`
- Binary data of the downloaded file
- Headers:
  - `Content-Disposition: attachment; filename="example_file.txt"`

**Error Responses:**
- `404 Not Found` - Job not found or file doesn't exist
- `400 Bad Request` - Download not completed yet

---

### 7. Start Seeding
**POST** `/api/torrents/seed`

Registers a torrent file and data file for seeding. The data file is saved to a permanent location.

**Request:**
- Content-Type: `multipart/form-data`
- Form fields:
  - `torrent` (file) - The .torrent file (required)
  - `file` (file) - The actual data file to seed (required)

**Response:**
```json
{
  "status": "seeding",
  "infoHash": "5c03506aa73be7824eac651d0980bcdb912cfa81",
  "filePath": "/Users/username/bittorrent-downloads/example_file.txt",
  "message": "Torrent is now being seeded"
}
```

**Note:** The data file is automatically saved to `~/bittorrent-downloads/` directory. Seeding continues as long as the server is running.

---

### 8. Get Active Torrents
**GET** `/api/torrents`

Returns a list of all active torrents currently being seeded.

**Response:**
```json
[]
```

**Note:** Currently returns empty list. Full implementation pending.

---

### 9. Get Torrent Status
**GET** `/api/torrents/{infoHash}/status`

Gets the status of a specific torrent.

**Path Parameters:**
- `infoHash` (string) - Hex-encoded info hash (40 characters)

**Response:**
```json
{
  "infoHash": "5c03506aa73be7824eac651d0980bcdb912cfa81",
  "status": "Seeding: Yes | InfoHash: 5c03506aa73be7824eac651d0980bcdb912cfa81",
  "seeding": true
}
```

---

### 10. Get Torrent Peers
**GET** `/api/torrents/{infoHash}/peers`

Gets the list of known peers for a torrent.

**Path Parameters:**
- `infoHash` (string) - Hex-encoded info hash (40 characters)

**Response:**
```json
{
  "infoHash": "5c03506aa73be7824eac651d0980bcdb912cfa81",
  "peers": [
    {
      "ip": "127.0.0.1",
      "port": "6882"
    },
    {
      "ip": "127.0.0.1",
      "port": "6883"
    }
  ],
  "count": 2
}
```

---

### 11. Stop Seeding
**DELETE** `/api/torrents/{infoHash}`

Stops seeding a torrent and removes it from active torrents.

**Path Parameters:**
- `infoHash` (string) - Hex-encoded info hash (40 characters)

**Response:**
```json
{
  "infoHash": "5c03506aa73be7824eac651d0980bcdb912cfa81",
  "message": "Stop seeding not yet implemented"
}
```

**Note:** Implementation pending.

---

## Error Responses

All endpoints return appropriate HTTP status codes:

- `200 OK` - Success
- `202 Accepted` - Request accepted, processing asynchronously
- `400 Bad Request` - Invalid request parameters
- `404 Not Found` - Resource not found
- `500 Internal Server Error` - Server error

Error responses follow this format:
```json
{
  "error": "Error message describing what went wrong"
}
```

---

## Notes for Frontend Integration

1. **File Uploads**: All endpoints that require torrent files use `multipart/form-data` with form field name `file`.

2. **Info Hash Format**: Info hashes are hex-encoded strings (40 characters for SHA-1).

3. **Async Downloads**: Downloads are asynchronous. Use the job ID to poll for status:
   - Start download: `POST /api/torrents/download` → get `jobId`
   - Poll status: `GET /api/torrents/download/{jobId}/status`
   - Download file when completed: `GET /api/torrents/download/{jobId}/file`

4. **File Storage**: All downloaded and seeded files are saved to `~/bittorrent-downloads/` directory and persist across server restarts.

5. **Seeding**: After downloading a file, it automatically starts seeding. Use the status endpoint to check seeding status.

6. **CORS**: If connecting from a frontend on a different origin, ensure CORS is configured in Spring Boot.

7. **PEX Support**: The client supports Peer Exchange (PEX) protocol for decentralized peer discovery, reducing dependency on trackers.

---

## Example Usage

### Complete Download Flow

1. **Start Download:**
```bash
curl -X POST -F "file=@example.torrent" \
     http://localhost:8080/api/torrents/download
```

Response:
```json
{
  "jobId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "started",
  "message": "Download started. Use /api/torrents/download/550e8400-e29b-41d4-a716-446655440000/status to check progress."
}
```

2. **Check Status:**
```bash
curl http://localhost:8080/api/torrents/download/550e8400-e29b-41d4-a716-446655440000/status
```

3. **Download Completed File:**
```bash
curl http://localhost:8080/api/torrents/download/550e8400-e29b-41d4-a716-446655440000/file \
     --output downloaded_file.txt
```
