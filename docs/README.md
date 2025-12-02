# BitTorrent Client in Java (Spring Boot API)

This project is a BitTorrent client implemented in Java. It was originally a command-line tool that has been refactored into a Spring Boot web application, exposing its core functionality as a REST API.

The client can parse `.torrent` files and magnet links, communicate with trackers to find peers, download complete files from those peers, and seed files to other peers. It also supports **Peer Exchange (PEX)** for decentralized peer discovery.

## 🛠️ Tech Stack

*   Java 21
*   Spring Boot: Used to create the REST API and run the web server.
*   Maven: For dependency management and building the project.
*   OkHttp: As the HTTP client for tracker communication [cite: `TrackerClient.java`](src/main/java/bittorrent/tracker/TrackerClient.java).
*   Gson: For JSON serialization in the API [cite: `BitTorrentService.java`](src/main/java/bittorrent/service/BitTorrentService.java).

## 📂 Project Structure
For more context (and for your beloved llms), refer [CONTEXT.md](CONTEXT.md)

Here is an overview of the key packages and files based on your project layout:

```
BITTORRENT-JAVA/
├── pom.xml                 # Maven build configuration
│
├── src/main/java/
│   └── bittorrent/
│       ├── BitTorrentApplication.java  # Spring Boot entry point
│       ├── Main.java                 # CLI entry point (for testing)
│       │
│       ├── controller/             # REST API definitions
│       │   └── BitTorrentController.java
│       │
│       ├── service/                # Core business logic
│       │   ├── BitTorrentService.java
│       │   └── DownloadJob.java    # Download job tracking
│       │
│       ├── bencode/                # Bencode (de)serializer classes
│       ├── magnet/                 # Magnet link parser
│       ├── peer/                   # Peer connection & wire protocol logic
│       │   ├── Peer.java           # Peer connection handler
│       │   ├── PeerServer.java     # Incoming peer connection server
│       │   ├── SwarmManager.java   # Peer swarm management
│       │   ├── PeerConnectionManager.java  # Active connection management
│       │   └── protocol/           # BitTorrent protocol messages
│       ├── torrent/                # Data models for .torrent files
│       ├── tracker/                # Tracker HTTP client logic
│       └── util/                   # SHA-1 and Network utilities
│
├── .gitignore              # Git ignore file
├── sample.torrent          # Example torrent file for testing
└── ... (other config files)
```

## ⚠️ Current Limitations

*   **Single File Mode Only**: This client currently supports only single-file torrents. It assumes the `.torrent` metadata describes a single file structure. Multi-file torrents (containing a `files` list in the info dictionary) are not yet supported and may cause the download to fail or behave unexpectedly.

## ✅ Features

*   **REST API**: Full REST API for frontend integration
*   **Asynchronous Downloads**: Downloads are processed asynchronously with job tracking
*   **Inbound Peer Connections**: The client runs a `PeerServer` that listens on port `6881` for incoming connections from other peers, enabling true seeding after downloads complete.
*   **Client Bitfield Tracking**: The client maintains a `BitSet` to track which pieces have been successfully downloaded and verified, preventing upload of invalid data.
*   **Peer Exchange (PEX)**: Supports PEX protocol for decentralized peer discovery, reducing dependency on trackers.
*   **Multi-Peer Downloads**: Downloads pieces from multiple peers in parallel using round-robin scheduling.
*   **Permanent File Storage**: All downloaded files are saved to `~/bittorrent-downloads/` and persist across server restarts.
*   **State Persistence**: Torrent files, download jobs, and seeding state are automatically persisted and restored on restart.
*   **Resume Capability**: Download jobs automatically resume from last progress after server restart.
*   **Thread-Safe Operations**: All shared data structures use thread-safe collections for concurrent access.

## ⚙️ Configuration

The client can be configured via `src/main/resources/application.properties`. Key settings include:

| Property | Default | Description |
|----------|---------|-------------|
| `bittorrent.peer-id` | `42112233445566778899` | Your client's 20-character peer ID |
| `bittorrent.listen-port` | `6881` | Port for incoming peer connections |
| `server.port` | `8080` | HTTP API server port |

**Note**: 
- Downloaded files are automatically saved to `~/bittorrent-downloads/` directory.
- Torrent files, download jobs, and seeding state are persisted in `~/.bittorrent/` directory.
- All state is automatically restored on server restart.

## ⚙️ How to Run (Development)

You must have Java (JDK 21+) and Maven installed.

All commands should be run from the project's root directory (where `pom.xml` is located).

### Run as Spring Boot Application (Recommended)

This method uses the Spring Boot plugin to compile and run the application in one step. It's the fastest way to get the server running.

```bash
mvn spring-boot:run
```

Once running, the server will be available at `http://localhost:8080`.

### Build JAR and Run

```bash
mvn clean package
java -jar target/java_bittorrent.jar
```

### Run as CLI (Testing)

For testing purposes, you can still run the CLI:

```bash
mvn clean package
java -jar target/java_bittorrent.jar <command> <args>
```

## 🎛️ React Control Center

`react-frontend/` hosts a Vite + React SPA tailored to BitTorrent workflows:

Highlights:
- **Torrent Analyzer** – drag a `.torrent` into the analyzer to preview announce URLs, info hash, piece sizes, and totals before launching a job.
- **Download Manager** – start asynchronous downloads, jump straight into telemetry for the new job, and stream progress via SSE.
- **Active Transfers** – auto-refreshing table that merges downloading and seeding torrents, showing speeds, completion, and quick Inspect actions.
- **Job Drawer** – floating telemetry panel with per-peer throughput, choke/interested flags, piece-source history, and links to fetch the completed payload when ready.
- **Peer & Tracker Tools** – fetch swarm membership for any info hash or manually inject a peer to bootstrap when trackers are down.
- **Seeding Station** – pair a local payload with metadata, announce to the tracker, and watch it appear in the Transfers table.
- **Torrent Builder** – generate a `.torrent` from any payload directly in the browser; the API returns the ready-to-share torrent file.

### Running the React UI

```bash
cd react-frontend
npm install
npm run dev
```

By default Vite proxies `/api` to the BitTorrent service on `http://localhost:8081` (the tracker continues to run on `8080`). Ensure both servers are up, then run `npm run build` for a production bundle when you’re ready to deploy.

## 📖 API Endpoints

For complete API documentation, see [API_ENDPOINTS.md](API_ENDPOINTS.md).

### Quick Reference

**Core Endpoints:**
- `GET /api/` - Health check
- `POST /api/torrents/info` - Get torrent metadata
- `POST /api/torrents/download` - Start async download (returns job ID)
- `GET /api/torrents/download/{jobId}/status` - Check download progress
- `GET /api/torrents/download/{jobId}/file` - Download completed file
- `POST /api/torrents/download/piece/{pieceIndex}` - Download specific piece
- `POST /api/torrents/seed` - Start seeding a file
- `GET /api/torrents/{infoHash}/status` - Get torrent status
- `GET /api/torrents/{infoHash}/peers` - Get known peers

### Example: Complete Download Flow

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

## 🧪 Testing

### PEX Testing
For testing Peer Exchange functionality, see [PEX_TESTING_GUIDE.md](PEX_TESTING_GUIDE.md).

### General Testing
For general testing procedures, see [TESTING.md](TESTING.md).

## 🏗️ Architecture

The application follows a layered architecture:

1. **Controller Layer** (`controller/`): Handles HTTP requests and responses
2. **Service Layer** (`service/`): Contains business logic and orchestrates operations
3. **Protocol Layer** (`peer/`, `tracker/`): Implements BitTorrent protocol
4. **Data Layer** (`torrent/`, `bencode/`): Handles data models and serialization

For detailed architecture information, see [CONTEXT.md](CONTEXT.md).

## 🔧 Backend Status

The backend is **production-ready** for frontend integration:

✅ **Non-blocking operations** - All long-running operations are async  
✅ **Resource management** - Proper cleanup of connections and files  
✅ **Thread safety** - All shared data structures are thread-safe  
✅ **Error handling** - Proper error responses and exception handling  
✅ **State persistence** - Torrent files, download jobs, and seeding state persist across restarts  
✅ **Resume capability** - Downloads automatically resume from last progress  
✅ **Scalability** - Can handle multiple concurrent requests  

## 📝 Notes

- **File Storage**: All downloads are saved to `~/bittorrent-downloads/` directory
- **State Persistence**: 
  - Torrent files: `~/.bittorrent/torrents/`
  - Download jobs: `~/.bittorrent/download_jobs.json`
  - Seeding torrents: `~/.bittorrent/seeding_torrents.json`
  - Known peers (PEX): `~/.bittorrent/known_peers.json`
- **Seeding**: After downloading, files automatically start seeding and persist across restarts
- **PEX**: Peer Exchange is enabled by default and helps discover peers without relying solely on trackers
- **Async Downloads**: Downloads are processed asynchronously to prevent HTTP timeouts
- **Auto-Resume**: All active downloads and seeding torrents are automatically restored on server restart
