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

### 4. Download Complete File
**POST** `/api/torrents/download`

Downloads the complete file from a torrent. After download, the file is automatically registered for seeding.

**Request:**
- Content-Type: `multipart/form-data`
- Form field: `file` (torrent file)

**Response:**
- Content-Type: `application/octet-stream`
- Binary data of the complete file

**Note:** The download will continue seeding after completion.

---

### 5. Start Seeding
**POST** `/api/torrents/seed`

Registers a torrent file and data file for seeding.

**Request:**
- Content-Type: `multipart/form-data`
- Form fields:
  - `torrent` (file) - The .torrent file
  - `file` (file) - The actual data file to seed

**Response:**
```json
{
  "status": "seeding",
  "infoHash": "5c03506aa73be7824eac651d0980bcdb912cfa81",
  "message": "Torrent is now being seeded"
}
```

---

### 6. Get Active Torrents
**GET** `/api/torrents`

Returns a list of all active torrents currently being seeded.

**Response:**
```json
[
  {
    "infoHash": "...",
    "name": "...",
    "status": "seeding"
  }
]
```

**Note:** Currently returns empty list. Implementation pending.

---

### 7. Get Torrent Status
**GET** `/api/torrents/{infoHash}/status`

Gets the status of a specific torrent.

**Path Parameters:**
- `infoHash` (string) - Hex-encoded info hash

**Response:**
```json
{
  "infoHash": "5c03506aa73be7824eac651d0980bcdb912cfa81",
  "status": "Seeding: Yes | InfoHash: ...",
  "seeding": true
}
```

---

### 8. Get Torrent Peers
**GET** `/api/torrents/{infoHash}/peers`

Gets the list of known peers for a torrent.

**Path Parameters:**
- `infoHash` (string) - Hex-encoded info hash

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

### 9. Stop Seeding
**DELETE** `/api/torrents/{infoHash}`

Stops seeding a torrent and removes it from active torrents.

**Path Parameters:**
- `infoHash` (string) - Hex-encoded info hash

**Response:**
```json
{
  "infoHash": "5c03506aa73be7824eac651d0980bcdb912cfa81",
  "message": "Stop seeding not yet implemented"
}
```

**Note:** Implementation pending.

---

## Removed/Deprecated Endpoints

The following endpoints were removed as they were redundant or not suitable for frontend integration:

1. **GET `/api/decode`** - Utility endpoint for decoding bencoded strings (not needed for frontend)
2. **GET `/api/handshake`** - Low-level peer handshake operation (not needed for frontend)
3. **GET `/api/debug/status`** - Replaced by `/api/torrents/{infoHash}/status`
4. **POST `/api/info`** - Replaced by `/api/torrents/info` (now returns JSON instead of string)
5. **POST `/api/download`** - Replaced by `/api/torrents/download` (better RESTful naming)

---

## Error Responses

All endpoints return appropriate HTTP status codes:

- `200 OK` - Success
- `400 Bad Request` - Invalid request parameters
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

3. **Seeding**: After downloading a file, it automatically starts seeding. Use the status endpoint to check seeding status.

4. **Async Operations**: Currently, downloads are synchronous. For large files, consider implementing async job tracking in the future.

5. **CORS**: If connecting from a frontend on a different origin, ensure CORS is configured in Spring Boot.

