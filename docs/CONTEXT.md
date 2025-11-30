# CONTEXT.md - BitTorrent Client Project Overview

## Purpose
This document provides a comprehensive overview for developers/AI agents working on this BitTorrent client. It explains the architecture, key flows, and how components interact.

## Quick Facts
- **Language**: Java 21
- **Framework**: Spring Boot (REST API)
- **Protocol**: BitTorrent peer-to-peer protocol
- **Key Libraries**: OkHttp (HTTP client), Gson (JSON), Lombok

---

## Architecture Overview

### High-Level Flow
```
User → REST API → Service Layer → Protocol Implementation → Network
  ↓                    ↓                    ↓                   ↓
Request         Business Logic      Peer/Tracker         TCP/HTTP
```

### Package Structure
```
bittorrent/
├── controller/          REST endpoints (user-facing API)
├── service/             Business logic orchestration
│   ├── BitTorrentService.java      Main service orchestrating operations
│   ├── DownloadJob.java            Download job tracking for async operations
│   ├── TorrentProgressService.java Real-time progress updates via SSE
│   └── storage/                     Persistence services
│       └── TorrentPersistenceService.java  State persistence (torrents, jobs, seeding)
├── torrent/             Data models (Torrent, TorrentInfo)
├── tracker/             Tracker communication (get peer list)
├── peer/                Peer protocol implementation (download pieces)
│   ├── Peer.java            Connection to other peer (outbound/inbound)
│   ├── PeerServer.java      Listens for incoming peer connections (port 6881)
│   ├── SwarmManager.java    Manages known/active/dropped peers per torrent
│   ├── PeerConnectionManager.java  Manages active peer connections
│   └── storage/            Persistence for peer state
│       └── SwarmPersistenceService.java  Persists known peers (PEX)
├── bencode/             Bencode serialization/deserialization
├── magnet/              Magnet link support
└── util/                SHA-1 hashing, network utilities
```

---

## Core Components

### 1. **TorrentInfo & Torrent** (`torrent/`)
**Purpose**: Represent .torrent file metadata

```java
TorrentInfo: {
  hash: byte[]           // SHA-1 of info dict (identifies torrent)
  length: long           // Total file size
  name: String           // File/folder name
  pieceLength: int       // Size of each piece (e.g., 262144 = 256KB)
  pieces: List<byte[]>   // SHA-1 hashes for each piece (20 bytes each)
}

Torrent: {
  announce: String       // Tracker URL
  info: TorrentInfo
}
```

**Key Method**: `TorrentInfo.of(Map)` - Parses bencoded dictionary, splits concatenated piece hashes into individual 20-byte chunks.

### 2. **TrackerClient** (`tracker/`)
**Purpose**: Communicate with tracker to get peer list

**Flow**:
```
1. Build HTTP GET request with:
   - info_hash (URL-encoded)
   - peer_id (our client ID)
   - port (listening port: 6881)
   - uploaded/downloaded/left (stats)
   - compact=1 (binary peer format)

2. Tracker responds with bencoded:
   - interval: How often to re-announce
   - peers: Binary-packed peer list (6 bytes per IPv4: 4B IP + 2B port)

3. Parse response into List<InetSocketAddress>
```

**Output**: List of peer IP:PORT that have the file

### 3. **Peer** (`peer/`)
**Purpose**: Handle BitTorrent peer protocol (handshake, message exchange, downloading)

#### **3.1 Handshake**
```java
Peer.connect(socket, torrent)
```

**Wire format**:
```
[19]["BitTorrent protocol"][8 reserved][20B info_hash][20B peer_id]
```

**What happens**:
1. Send handshake with torrent's info_hash
2. Peer responds with same structure
3. Verify peer's info_hash matches (they have the torrent)
4. Extract peer_id and extension support flag
5. Return connected Peer object

#### **3.2 Message Exchange**
All messages follow: `[4B length][1B type_id][payload]`

**Key message types** (see `Message.java`):
- `Bitfield`: Which pieces peer has (bitmap)
- `Interested`: "I want to download from you"
- `Unchoke`: "You can download from me now"
- `Request`: Request a block (piece_index, offset, length)
- `Piece`: Block data response

**Message flow managed by**:
- `send(Message)`: Serialize and send message
- `receive()`: Deserialize incoming message
- `waitFor(Predicate)`: Wait for specific message type (uses internal queue)

#### **3.3 Downloading a Piece**
```java
peer.downloadPiece(torrentInfo, pieceIndex)
```

**Critical concept - Pieces vs Blocks**:
- **Piece**: Large chunk (256KB-1MB+), has SHA-1 hash in .torrent file
- **Block**: Small chunk (16KB), actual network transfer unit
- **Why?** Minimize data loss on connection failure

**Download flow**:
```
1. awaitBitfield()        → Wait for peer's piece availability
2. sendInterested()       → Express interest, wait for Unchoke
3. Break piece into 16KB blocks
4. Send Request message for each block
5. Receive Piece messages (may arrive out of order)
6. Assemble blocks into complete piece
7. Verify SHA-1 hash matches torrentInfo.pieces[pieceIndex]
8. Return byte[] of piece data
```

**Choke/Unchoke**: Flow control mechanism. Peers "choke" to limit upload bandwidth. Must wait for "unchoke" before downloading.

**Client Bitfield Tracking**: The client maintains a `BitSet` to track which pieces have been successfully downloaded and verified. After each piece's SHA-1 hash is verified in `downloadPiece()`, the corresponding bit is set. Before uploading a piece in `handlePieceRequest()`, the client checks this bitfield to ensure it actually has the requested piece. The client also sends its bitfield to peers after the handshake completes, allowing peers to know which pieces are available for download.

#### **3.4 Peer Exchange (PEX)**
The client supports the PEX protocol extension for decentralized peer discovery.

**Extension Negotiation**:
- During handshake, peers advertise extension support via reserved bits
- Extension handshake (message id=0) exchanges supported extension IDs
- `ut_pex` extension ID is negotiated (typically 43)

**PEX Message Flow**:
```
1. After connection established, send periodic PEX updates (every 15 seconds)
2. PEX update contains:
   - added: List of newly discovered peer addresses
   - dropped: List of peers that disconnected
3. On receiving PEX update:
   - Add new peers to SwarmManager
   - Broadcast newly discovered peers to other connected peers
   - Exclude self and current peer from updates
```

**Key Components**:
- `SwarmManager`: Tracks known, active, and dropped peers per torrent (thread-safe)
- `PeerConnectionManager`: Manages active peer connections and broadcasts PEX updates
- `Peer.runPexUpdateLoop()`: Daemon thread that sends periodic PEX updates
- Rate limiting prevents sending duplicate peers too frequently

### 4. **PeerServer** (`peer/`)
**Purpose**: Listen for inbound peer connections and handle seeding

**Architecture**:
```java
PeerServer: {
  port: 6881                     // Standard BitTorrent port
  activeTorrents: ConcurrentHashMap<String, TorrentInfo>  // InfoHash -> TorrentInfo
  torrentFiles: ConcurrentHashMap<String, File>          // InfoHash -> File
  executorService: ExecutorService  // Thread pool for peer connections
}
```

**Flow**:
```
1. Spring Boot app starts → @PostConstruct calls peerServer.start()
2. ServerSocket listens on port 6881 in background thread
3. When download completes → registerTorrent(torrentInfo, file)
4. Other peers connect → acceptLoop() accepts socket
5. handleConnection():
   a. Read handshake from connecting peer
   b. Verify info_hash against activeTorrents
   c. Send handshake response with our peer_id
   d. Create Peer instance to handle bidirectional communication
   e. Send our bitfield immediately
6. Peer instance handles upload requests via handlePieceRequest()
```

**Key Methods**:
- `start()`: Creates ServerSocket on port 6881, starts accept loop thread
- `registerTorrent(TorrentInfo, File)`: Makes torrent available for seeding
- `getTorrentStatus(byte[] infoHash)`: Check if seeding a torrent (for debug endpoint)
- `handleConnection(Socket)`: Perform handshake, create Peer instance
- `stop()`: Shutdown server and thread pool (@PreDestroy)

**Integration**:
- `BitTorrentService` injects `PeerServer` as Spring dependency
- After `downloadPiece()` or `downloadFile()`, service calls `peerServer.registerTorrent()`
- Lifecycle managed by Spring: starts on app startup, stops on shutdown

### 5. **SwarmManager** (`peer/`)
**Purpose**: Manages peer swarm state per torrent (thread-safe)

**Architecture**:
```java
SwarmManager: {
  swarms: ConcurrentHashMap<String, SwarmState>  // InfoHash -> State
  
  SwarmState: {
    knownPeers: ConcurrentHashMap.newKeySet()     // All known peers
    activePeers: ConcurrentHashMap.newKeySet()    // Currently connected
    droppedPeers: ConcurrentHashMap.newKeySet()  // Recently disconnected
    lastSentTime: ConcurrentHashMap<Address, Long> // Rate limiting
  }
}
```

**Key Methods**:
- `registerTrackerPeers()`: Add peers from tracker announcement
- `onPexPeersDiscovered()`: Add peers discovered via PEX
- `registerActivePeer()`: Mark peer as active
- `unregisterActivePeer()`: Mark peer as dropped
- `acquirePeers()`: Get peers for connection (excludes active)
- `getPeersForPex()`: Get peers suitable for PEX update
- `isPeerKnown()`: Check if peer is already in swarm

### 6. **PeerConnectionManager** (`peer/`)
**Purpose**: Manages active peer connections and facilitates PEX broadcasting

**Architecture**:
```java
PeerConnectionManager: {
  activeConnections: ConcurrentHashMap<String, List<Peer>>  // InfoHash -> Peers
}
```

**Key Methods**:
- `registerConnection()`: Add peer to active connections
- `unregisterConnection()`: Remove peer from active connections
- `broadcastPexUpdate()`: Send PEX update to all connected peers
- `broadcastNewPeers()`: Broadcast newly discovered peers (filters duplicates)

### 7. **BitTorrentService** (`service/`)
**Purpose**: Orchestrate operations, called by REST controllers

**Key methods**:
- `loadTorrent(String)`: Parse .torrent file
- `downloadPiece(String, int)`: Download single piece from peers
- `startDownload(String, String)`: Start async download job (returns job ID, saves torrent file)
- `getDownloadJob(String)`: Get download job status
- `downloadFile(String)`: Legacy synchronous download (CLI compatibility)
- `seed(String, String)`: Register file for seeding (non-blocking, saves torrent file and state)
- `loadPersistedState()`: Load and resume persisted downloads/seeding on startup
- `saveState()`: Save download jobs state (called periodically)

**Async Download Flow**:
```
1. startDownload() creates DownloadJob with unique job ID
2. Torrent file saved to ~/.bittorrent/torrents/ (persisted)
3. Downloads processed in background via ExecutorService
4. Progress tracked: completedPieces / totalPieces
5. Download job state saved every 30 seconds
6. Files saved to ~/bittorrent-downloads/ (permanent storage)
7. Peer connections closed after download completes
8. File automatically registered for seeding via PeerServer
9. Seeding state saved to ~/.bittorrent/seeding_torrents.json
```

**DownloadJob** (`service/DownloadJob.java`):
- Tracks download status (PENDING, DOWNLOADING, COMPLETED, FAILED)
- Stores progress (completed pieces, total pieces, percentage)
- Links to downloaded file when complete
- Thread-safe status updates

**Seeding Integration**:
- After successful download, files are automatically registered with `peerServer.registerTorrent()`
- Files saved to permanent location: `~/bittorrent-downloads/`
- Torrent file and seeding state saved to `~/.bittorrent/`
- Seeding state persists across restarts and is automatically restored
- On startup, all persisted seeding torrents are re-registered and re-announced to tracker

### 5. **BencodeDeserializer** (`bencode/`)
**Purpose**: Parse .torrent files (bencoded format)

**Bencode types**:
- Strings: `4:spam` → "spam"
- Integers: `i42e` → 42
- Lists: `l4:spami42ee` → ["spam", 42]
- Dictionaries: `d3:bar4:spame` → {"bar": "spam"}

---

## Complete Request Flow Examples

### Example 1: Download a Piece
```
POST /api/torrents/download/piece/0 (with sample.torrent)
    ↓
BitTorrentController.downloadPiece()
    ↓
BitTorrentService.downloadPiece(path, 0)
    ↓
1. Load & parse .torrent file → Torrent object
2. TrackerClient.announce(torrent) → Get peer list
3. SwarmManager.registerTrackerPeers() → Add to known peers
4. Connect to first peer → Peer.connect() → handshake
5. Extension negotiation → PEX support advertised
6. peer.downloadPiece(torrentInfo, 0)
   a. awaitBitfield() → peer sends which pieces they have
   b. sendInterested() → wait for Unchoke
   c. Send Request for each 16KB block
   d. Receive Piece messages
   e. Verify SHA-1 hash
7. Return piece data to user
```

### Example 1b: Async Download Complete File
```
POST /api/torrents/download (with sample.torrent)
    ↓
BitTorrentController.startDownload()
    ↓
BitTorrentService.startDownload(path, fileName)
    ↓
1. Create DownloadJob with unique job ID
2. Return job ID immediately (202 Accepted)
3. Background thread:
   a. Load torrent, get peers from tracker/PEX
   b. Connect to multiple peers (round-robin)
   c. Download all pieces in parallel
   d. Write to ~/bittorrent-downloads/{fileName}
   e. Update job status: COMPLETED
   f. Close peer connections
   g. Register file for seeding
4. Client polls GET /api/torrents/download/{jobId}/status
5. When complete, GET /api/torrents/download/{jobId}/file
```

### Example 2: Start Seeding
```
POST /api/torrents/seed (with torrent file and data file)
    ↓
BitTorrentController.startSeeding()
    ↓
BitTorrentService.seed(torrentPath, filePath)
    ↓
1. Load & parse .torrent file → Torrent object
2. Save data file to ~/bittorrent-downloads/ (permanent location)
3. peerServer.registerTorrent(torrentInfo, file)
4. TrackerClient.announce(torrent) → Announce to tracker
5. Return immediately (non-blocking)
6. PeerServer handles incoming connections in background
7. Peers can now download pieces from this client
```

---

## Key Concepts Reference

### Info Hash vs Piece Hash
- **Info Hash** (20 bytes): SHA-1 of entire torrent metadata. Used in tracker announces and handshakes. Identifies the torrent globally.
- **Piece Hashes** (list of 20 bytes): SHA-1 of each piece. Used to verify downloaded data integrity.

### Message Descriptors
`MessageDescriptors` class provides registry pattern for serializing/deserializing messages by type ID (0-20). Each message type has a descriptor with serialize/deserialize functions.

### Extension Protocol
The client supports two extensions:
1. **ut_metadata**: For magnet links (no metadata available initially)
   - Set bit 20 in handshake reserved bytes
   - Send extension handshake with `ut_metadata` support
   - Request metadata pieces from peer
   - Reconstruct TorrentInfo from metadata
   - Proceed with normal download

2. **ut_pex** (Peer Exchange): For decentralized peer discovery
   - Negotiated during extension handshake
   - Periodic PEX updates (every 15 seconds)
   - Exchanges peer addresses between connected peers
   - Reduces dependency on trackers

### Compact Peer Format
Tracker returns peers as binary string:
- **IPv4**: 6 bytes per peer (4B IP + 2B port)
- **IPv6**: 18 bytes per peer (16B IP + 2B port)

Example: `[192.168.1.100][6881][10.0.0.5][51413]...`

---

## Common Operations

### Adding a New Endpoint
1. Add method in `BitTorrentController` with appropriate mapping
2. Implement business logic in `BitTorrentService`
3. Use existing protocol implementations (`Peer`, `TrackerClient`)

### Debugging Protocol Issues
- Check `System.err.println()` statements in `Peer.java` (shows all sent/received messages)
- Verify info_hash matches between client and peer
- Check choke/unchoke state before requesting pieces
- Ensure SHA-1 verification in `downloadPiece()` passes

### Testing
Use sample.torrent with these endpoints:
```bash
# Get torrent info
curl -X POST -F "file=@sample.torrent" http://localhost:8080/api/torrents/info

# Download piece 0
curl -X POST -F "file=@sample.torrent" http://localhost:8080/api/torrents/download/piece/0 --output piece_0.bin

# Start async download
curl -X POST -F "file=@sample.torrent" http://localhost:8080/api/torrents/download

# Check download status (use jobId from previous response)
curl http://localhost:8080/api/torrents/download/{jobId}/status
```

---

## Implemented Features

✅ **Multi-peer downloads**: Downloads pieces from multiple peers in parallel using round-robin scheduling.

✅ **Peer Exchange (PEX)**: Fully implemented PEX protocol for decentralized peer discovery, reducing tracker dependency.

✅ **Async downloads**: Downloads are processed asynchronously with job tracking, preventing HTTP timeouts.

✅ **Thread-safe operations**: All shared data structures use `ConcurrentHashMap` for safe concurrent access.

✅ **Resource management**: Peer connections are properly closed after downloads complete.

✅ **Permanent file storage**: All downloads saved to `~/bittorrent-downloads/` directory, persisting across restarts.

✅ **State persistence**: Torrent files, download jobs, and seeding state are automatically persisted and restored on restart.

✅ **Resume capability**: Download jobs automatically resume from last progress after server restart.

✅ **Peer connection tracking**: `PeerConnectionManager` tracks active connections and facilitates PEX broadcasting.

✅ **Swarm management**: `SwarmManager` tracks known, active, and dropped peers per torrent (thread-safe, with persistence).

## Missing Implementations (TODOs)

3.  **Multi-file torrent support**: Only single-file torrents work. Need `FileManager` abstraction for multi-file torrents.

4.  **Smart peer selection**: No intelligent peer selection (fastest, closest, etc.). Currently uses round-robin.

5.  **Stop seeding endpoint**: `DELETE /api/torrents/{infoHash}` endpoint exists but not fully implemented.

6.  **Active torrents list**: `GET /api/torrents` returns empty list. Need to track and return active torrents.

---

## State Persistence

### TorrentPersistenceService
**Purpose**: Persists torrent files, download jobs, and seeding state to disk.

**Storage Locations**:
- Torrent files: `~/.bittorrent/torrents/{infoHash}.torrent`
- Download jobs: `~/.bittorrent/download_jobs.json`
- Seeding torrents: `~/.bittorrent/seeding_torrents.json`

**Features**:
- Automatic saving every 30 seconds for download jobs
- Immediate save on torrent registration/seeding
- Automatic loading on startup (`@PostConstruct`)
- Graceful error handling (skips missing files)

**Resume Flow**:
1. On startup, `BitTorrentService.init()` calls `loadPersistedState()`
2. Loads download jobs from JSON, verifies files exist
3. Resumes downloads from last completed piece
4. Loads seeding torrents, re-registers with `PeerServer`
5. Re-announces all resumed torrents to tracker

### SwarmPersistenceService
**Purpose**: Persists known peers discovered via PEX.

**Storage**: `~/.bittorrent/known_peers.json`

**Features**:
- Saves known peers per torrent
- Loads on startup to bootstrap peer discovery
- Periodic saves (every 5 minutes)

## Next Steps (Prioritized Roadmap)

### Phase 1: Enhancements (Future)

---

### Phase 2: Core Features (Week 2)

#### 3. Multi-File Torrent Support
**Goal**: Support torrents with multiple files (directories).

**Implementation**:
- Parse `files` list from torrent metadata in `TorrentInfo`
- Create `FileManager` class to handle file/directory mapping
- Map piece offsets to correct files (handle boundaries)
- Update `Peer.handlePieceRequest()` to read from correct file
- Support directory creation for nested structures

**Files to create**: `FileManager.java`  
**Files to modify**: `TorrentInfo.java`, `Peer.java`, `BitTorrentService.java`

#### 4. Multi-Peer Parallel Downloads
**Goal**: Download different pieces simultaneously from multiple peers.

**Implementation**:
- Create `DownloadCoordinator` class
- Use `ExecutorService` with thread pool (5-10 threads)
- Assign pieces to different peers dynamically
- Track in-progress pieces to avoid duplication
- Handle peer failures (reassign piece to another peer)
- Aggregate results and write to file sequentially

**Files to create**: `DownloadCoordinator.java`  
**Files to modify**: `BitTorrentService.java`

---

### Phase 3: Optimization (Week 3)

#### 5. Smart Peer Selection Strategy
**Goal**: Choose fastest/best peers for downloading.

**Implementation**:
- Track per-peer metrics: download speed, latency, reliability
- Implement "rarest first" piece selection
- Prefer peers with rare pieces
- Rotate peers if performance degrades
- Add peer scoring system

**Files to create**: `PeerSelector.java`, `PeerMetrics.java`  
**Files to modify**: `DownloadCoordinator.java`

#### 6. Resume Capability
**Goal**: Resume interrupted downloads from where they stopped.

**Implementation**:
- Save bitfield to disk after each piece completes
- Save partial blocks within incomplete pieces
- Create `DownloadState` class to manage state
- On startup: check for existing state, load bitfield
- Only request missing pieces from peers

**Files to create**: `DownloadState.java`  
**Files to modify**: `BitTorrentService.java`, `Peer.java`

---

### Phase 4: Production Readiness (Week 4)

#### 7. Proper Choking Algorithm
**Goal**: Implement BitTorrent's tit-for-tat mechanism.

**Implementation**:
- Track upload speed per peer
- Unchoke top 4 fastest uploaders
- Implement optimistic unchoking (rotate every 30s)
- Send Choke/Unchoke messages appropriately
- Prevent bandwidth exhaustion

**Files to modify**: `PeerServer.java`, `Peer.java`

#### 8. Tracker Re-Announcement
**Goal**: Periodic tracker updates and proper lifecycle events.

**Implementation**:
- Schedule re-announcements every `interval` seconds
- Update uploaded/downloaded/left stats
- Send `stopped` event on shutdown
- Handle tracker failures gracefully
- Support multiple trackers (fallback)

**Files to modify**: `TrackerClient.java`, `BitTorrentService.java`

#### 9. Configuration System
**Goal**: Make hardcoded values configurable.

**Create `application.properties`**:
```properties
bittorrent.peer-id=<random-generated>
bittorrent.listen-port=6881
bittorrent.max-connections=50
bittorrent.download-dir=./downloads
bittorrent.max-upload-rate=1048576
bittorrent.max-download-rate=5242880
```

**Files to create**: `BitTorrentConfig.java` (Spring `@ConfigurationProperties`)  
**Files to modify**: `PeerServer.java`, `BitTorrentService.java`

#### 10. Enhanced Error Handling
**Goal**: Graceful failure recovery and user-friendly errors.

**Implementation**:
- Retry failed piece downloads (max 3 attempts)
- Handle peer disconnections without crashing
- Return proper HTTP status codes in controllers
- Add connection timeouts (30s for handshake, 60s for pieces)
- Log errors with context (peer IP, piece index, etc.)

**Files to modify**: `BitTorrentController.java`, `Peer.java`, `TrackerClient.java`

---

### Quick Wins (Can implement anytime)

- **Randomize Peer ID**: Generate random 20-byte peer ID on startup
- **Replace System.err with Logging**: Use SLF4J/Logback for proper logging
- **Add Metrics Endpoint**: Track uploaded/downloaded bytes, active connections
- **Validate Torrent Files**: Check required fields before processing
- **Add Request Timeouts**: Prevent hanging on unresponsive peers

---

### Recommended Start Point

**Start with Phase 1 (Critical Fixes)**:
1. Fix peer connection lifecycle first - it's a memory leak bug
2. Then add persistent seeding state for better user experience

These two changes will make your client stable and reliable before adding more complex features.

---

## Important Notes

- **Peer ID**: Hardcoded as `"42112233445566778899"` (should be randomized in production)
- **Port**: Hardcoded as `6881` (should be configurable)
- **Error Handling**: Most errors propagate up; add proper error responses in production
- **Concurrency**: Current implementation is single-threaded per request
- **Security**: No validation of tracker URLs or peer IPs (potential SSRF/malicious tracker issues)

---

## References
- BitTorrent Protocol Specification: http://www.bittorrent.org/beps/bep_0003.html
- Extension Protocol (BEP 10): http://www.bittorrent.org/beps/bep_0010.html
- Magnet Links (BEP 9): http://www.bittorrent.org/beps/bep_0009.html
