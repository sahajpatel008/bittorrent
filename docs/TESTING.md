# BitTorrent P2P Testing Guide

This document describes how to test the complete BitTorrent peer-to-peer file transfer flow using the tracker, multiple peers with Spring Boot backends, and React frontends.

## Prerequisites

- Java 21+
- Maven
- Node.js & npm
- 5+ terminal windows (for full setup)

## Architecture Overview

```
                    ┌─────────────────────┐
                    │   Tracker Server    │
                    │     (Port 8080)     │
                    └──────────┬──────────┘
                               │
                    Announce / Peer Discovery
                               │
          ┌────────────────────┴────────────────────┐
          │                                         │
          ▼                                         ▼
┌───────────────────────┐             ┌───────────────────────┐
│      Peer 1 (Seeder)  │             │    Peer 2 (Leecher)   │
│                       │             │                       │
│  ┌─────────────────┐  │             │  ┌─────────────────┐  │
│  │  Spring Boot    │  │             │  │  Spring Boot    │  │
│  │  Backend: 8081  │  │             │  │  Backend: 8082  │  │
│  └─────────────────┘  │             │  └─────────────────┘  │
│                       │             │                       │
│  ┌─────────────────┐  │  Peer-to-  │  ┌─────────────────┐  │
│  │  BitTorrent     │◄─┼────Peer────┼─►│  BitTorrent     │  │
│  │  Port: 6881     │  │  Transfer  │  │  Port: 6882     │  │
│  └─────────────────┘  │             │  └─────────────────┘  │
│                       │             │                       │
│  ┌─────────────────┐  │             │  ┌─────────────────┐  │
│  │  React Frontend │  │             │  │  React Frontend │  │
│  │  Port: 5173     │  │             │  │  Port: 5174     │  │
│  └─────────────────┘  │             │  └─────────────────┘  │
└───────────────────────┘             └───────────────────────┘
```

## Port Summary

| Component | Port | Purpose |
|-----------|------|---------|
| **Tracker Server** | 8080 | HTTP announce endpoint & swarm management |
| **Peer 1 Backend** | 8081 | REST API for Peer 1 |
| **Peer 1 BitTorrent** | 6881 | Peer-to-peer file transfer |
| **Peer 1 Frontend** | 5173 | Web UI for Peer 1 |
| **Peer 2 Backend** | 8082 | REST API for Peer 2 |
| **Peer 2 BitTorrent** | 6882 | Peer-to-peer file transfer |
| **Peer 2 Frontend** | 5174 | Web UI for Peer 2 |

---

## Step-by-Step Testing

### Step 1: Build the Project

```bash
cd /path/to/bittorrent
mvn clean compile
```

### Step 2: Start the Tracker Server (Terminal 1)

```bash
cd /path/to/bittorrent
mvn spring-boot:run -Dspring-boot.run.main-class=bittorrent.tracker.server.TrackerServerApplication -Dspring-boot.run.arguments="--server.port=8080"
```

**Expected output:**
```
Loaded X swarms from storage.
Tomcat started on port 8080 (http) with context path ''
Started TrackerServerApplication in X.XXX seconds
```

### Step 3: Start Peer 1 - Seeder Backend (Terminal 2)

```bash
cd /path/to/bittorrent
mvn spring-boot:run -Dspring-boot.run.main-class=bittorrent.BitTorrentApplication -Dspring-boot.run.arguments="--server.port=8081 --bittorrent.listen-port=6881 --bittorrent.peer-id=42112233445566778899"
```

**Expected output:**
```
Tomcat initialized with port 8081 (http)
PeerServer listening on port 6881
Started BitTorrentApplication in X.XXX seconds
```

### Step 4: Start Peer 1 Frontend (Terminal 3)

```bash
cd /path/to/bittorrent/react-frontend
npm run dev -- --port 5173
```

**Expected output:**
```
VITE v5.x.x  ready in XXX ms

➜  Local:   http://localhost:5173/
```

### Step 5: Start Peer 2 - Leecher Backend (Terminal 4)

```bash
cd /path/to/bittorrent
mvn spring-boot:run -Dspring-boot.run.main-class=bittorrent.BitTorrentApplication -Dspring-boot.run.arguments="--server.port=8082 --bittorrent.listen-port=6882 --bittorrent.peer-id=99887766554433221100"
```

**Expected output:**
```
Tomcat initialized with port 8082 (http)
PeerServer listening on port 6882
Started BitTorrentApplication in X.XXX seconds
```

### Step 6: Start Peer 2 Frontend (Terminal 5)

```bash
cd /path/to/bittorrent/react-frontend
VITE_API_BASE=http://localhost:8082/api npm run dev -- --port 5174
```

**Expected output:**
```
VITE v5.x.x  ready in XXX ms

➜  Local:   http://localhost:5174/
```

---

## Testing the File Transfer

### On Peer 1 (Seeder) - http://localhost:5173

1. **Create/Upload a torrent file** with a file you want to share
2. **Start seeding** - this registers the torrent and announces to the tracker
3. The seeder will show status: "SEEDING"

### Verify Seeder Registered with Tracker

```bash
# Check tracker has the peer registered
curl "http://localhost:8080/api/tracker/swarms/<INFO_HASH>/peers"
```

**Expected response:**
```json
{
  "peers": [{"ip": "127.0.0.1", "port": 6881, "peerId": "42112233445566778899"}],
  "count": 1,
  "infoHash": "<INFO_HASH>"
}
```

### Manually Register Peer with Tracker (if needed)

If the automatic announce didn't work, manually register the seeder:

```bash
curl -X POST "http://localhost:8080/api/tracker/swarms/<INFO_HASH>/peers" \
  -H "Content-Type: application/json" \
  -d '{"ip": "127.0.0.1", "port": 6881, "peerId": "42112233445566778899"}'
```

### On Peer 2 (Leecher) - http://localhost:5174

1. **Upload the same .torrent file**
2. **Start download** - the leecher will:
   - Announce to tracker
   - Get Peer 1's address from tracker
   - Connect to Peer 1 on port 6881
   - Download pieces
3. Watch the progress bar fill up!

---

## Verifying the Transfer

### Check Active Torrents

```bash
# Peer 1 (Seeder)
curl "http://localhost:8081/api/torrents"

# Peer 2 (Leecher)  
curl "http://localhost:8082/api/torrents"
```

### Check Tracker Swarms

```bash
curl "http://localhost:8080/api/tracker/swarms/<INFO_HASH>/peers"
```

### Compare Files

After download completes, verify the files match:

```bash
md5sum original_file.mp4 downloaded_file.mp4
# Both should show the same hash
```

---

## Expected Log Flow

### Tracker Logs
```
Tracker announce: infoHash=<HASH> ip=127.0.0.1:6881 left=0 downloaded=X uploaded=0
Tracker announce: infoHash=<HASH> ip=127.0.0.1:6882 left=X downloaded=0 uploaded=0
```

### Seeder Logs (Peer 1)
```
Registered torrent for seeding: <INFO_HASH>
Attempting to pass tracker url: http://localhost:8080/announce
AnnounceResponse: {interval=1800, min interval=300, peers=...}
Accepted connection from /127.0.0.1:XXXXX
Handshake successful with /127.0.0.1:XXXXX
```

### Leecher Logs (Peer 2)
```
Attempting to pass tracker url: http://localhost:8080/announce
AnnounceResponse: {interval=1800, peers=[127.0.0.1:6881]}
Peer: trying to connect: /127.0.0.1:6881
RECV_LOOP: Bitfield[...]
RECV_LOOP: Piece[index=0, begin=0, block.length=...]
```

---

## Troubleshooting

### CORS Errors

If you see CORS errors in the browser console, ensure `application.properties` includes:

```properties
app.cors.allowed-origins=http://localhost:5173,http://localhost:5174,http://localhost:5175
```

Then restart the backend servers.

### Info Hash Mismatch (Tracker receives different hash)

If tracker logs show a different info hash than peers send (e.g., `3f3f3f...` instead of `f08c83...`), this is a URL encoding issue. The fix is in `TrackerController.java` to manually decode the info_hash using ISO-8859-1.

### No Peers Found

1. Ensure seeder started and announced **before** leecher
2. Check tracker has the peer:
   ```bash
   curl "http://localhost:8080/api/tracker/swarms/<INFO_HASH>/peers"
   ```
3. Manually register if needed (see above)

### Port Already in Use

```bash
# Find process using the port
lsof -i :8081
# Kill if needed
kill -9 <PID>
```

### Max Upload Size Exceeded

Add to `application.properties`:
```properties
spring.servlet.multipart.max-file-size=10GB
spring.servlet.multipart.max-request-size=10GB
```

---

## Quick Reference Commands

### Start All Services

```bash
# Terminal 1 - Tracker
mvn spring-boot:run -Dspring-boot.run.main-class=bittorrent.tracker.server.TrackerServerApplication -Dspring-boot.run.arguments="--server.port=8080"

# Terminal 2 - Peer 1 Backend
mvn spring-boot:run -Dspring-boot.run.main-class=bittorrent.BitTorrentApplication -Dspring-boot.run.arguments="--server.port=8081 --bittorrent.listen-port=6881 --bittorrent.peer-id=42112233445566778899"

# Terminal 3 - Peer 1 Frontend
cd react-frontend && npm run dev -- --port 5173

# Terminal 4 - Peer 2 Backend
mvn spring-boot:run -Dspring-boot.run.main-class=bittorrent.BitTorrentApplication -Dspring-boot.run.arguments="--server.port=8082 --bittorrent.listen-port=6882 --bittorrent.peer-id=99887766554433221100"

# Terminal 5 - Peer 2 Frontend
cd react-frontend && VITE_API_BASE=http://localhost:8082/api npm run dev -- --port 5174
```

### API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/torrents` | GET | List all active torrents |
| `/api/seed` | POST | Start seeding a file |
| `/api/download` | POST | Start downloading a torrent |
| `/api/tracker/swarms/{hash}/peers` | GET | Get peers for a swarm |
| `/api/tracker/swarms/{hash}/peers` | POST | Register peer manually |

---

## Message Types Reference

| Type ID | Name | Direction | Description |
|---------|------|-----------|-------------|
| 0 | Choke | S → L | Stop sending pieces |
| 1 | Unchoke | S → L | Allow piece requests |
| 2 | Interested | L → S | Want to download |
| 3 | NotInterested | L → S | Don't need pieces |
| 4 | Have | Both | Announce piece completion |
| 5 | Bitfield | S → L | Available pieces bitmap |
| 6 | Request | L → S | Request piece data |
| 7 | Piece | S → L | Piece data response |
| 8 | Cancel | L → S | Cancel pending request |
