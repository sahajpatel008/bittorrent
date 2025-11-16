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
├── torrent/             Data models (Torrent, TorrentInfo)
├── tracker/             Tracker communication (get peer list)
├── peer/                Peer protocol implementation (download pieces)
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

### 4. **BitTorrentService** (`service/`)
**Purpose**: Orchestrate operations, called by REST controllers

**Key methods**:
- `decode(String)`: Bencode → JSON
- `getInfo(String)`: Parse .torrent file
- `getPeers(String)`: Tracker announce → peer list
- `handshake(String, String)`: Connect to peer, return peer_id
- `downloadPiece(String, int)`: Full piece download from first available peer

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
POST /api/download/piece/0 (with sample.torrent)
    ↓
BitTorrentController.downloadPiece()
    ↓
BitTorrentService.downloadPiece(path, 0)
    ↓
1. Load & parse .torrent file → Torrent object
2. TrackerClient.announce(torrent) → Get peer list
3. Connect to first peer → Peer.connect() → handshake
4. peer.downloadPiece(torrentInfo, 0)
   a. awaitBitfield() → peer sends which pieces they have
   b. sendInterested() → wait for Unchoke
   c. Send Request for each 16KB block
   d. Receive Piece messages
   e. Verify SHA-1 hash
5. Write bytes to temp file
6. Return file to user
```

### Example 2: Handshake Only
```
GET /api/handshake?peer=167.99.63.30:6881&file=/path/to/file.torrent
    ↓
BitTorrentService.handshake(path, peerIpAndPort)
    ↓
1. Load .torrent → get info_hash
2. Open TCP socket to peer
3. Peer.connect(socket, torrent)
   - Send: [19][protocol][reserved][info_hash][peer_id]
   - Recv: [19][protocol][reserved][info_hash][peer_id]
   - Verify info_hash matches
4. Return peer_id as hex string
5. Close connection
```

---

## Key Concepts Reference

### Info Hash vs Piece Hash
- **Info Hash** (20 bytes): SHA-1 of entire torrent metadata. Used in tracker announces and handshakes. Identifies the torrent globally.
- **Piece Hashes** (list of 20 bytes): SHA-1 of each piece. Used to verify downloaded data integrity.

### Message Descriptors
`MessageDescriptors` class provides registry pattern for serializing/deserializing messages by type ID (0-20). Each message type has a descriptor with serialize/deserialize functions.

### Extension Protocol
For magnet links (no metadata available initially):
1. Set bit 20 in handshake reserved bytes
2. Send extension handshake with `ut_metadata` support
3. Request metadata pieces from peer
4. Reconstruct TorrentInfo from metadata
5. Proceed with normal download

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
curl -X POST -F "file=@sample.torrent" http://localhost:8080/api/info

# Download piece 0
curl -X POST -F "file=@sample.torrent" http://localhost:8080/api/download/piece/0 --output piece_0.bin
```

---

## Missing Implementations (TODOs)

1.  **Upload Correctness (Bitfield Check)**: The new upload logic in `handlePieceRequest()` doesn't verify if the client *actually has* a piece before sending it. A client-side bitfield (like a `BitSet`) must be implemented to track downloaded pieces, and this bitfield must be checked before uploading a block. This is the **top priority** to prevent sending bad data.

2.  **Persistent Seeding**: The client's `Peer` connections are wrapped in `try-with-resources` blocks (as seen in the `BitTorrentService` methods), meaning they close immediately after the download finishes. This should be refactored to keep connections open and continue seeding (uploading) after the file is 100% complete.

3.  **Multi-peer downloads**: Only uses first peer. Should parallelize across multiple peers.

4.  **Peer selection**: No smart peer selection (fastest, closest, etc.).

5.  **Resume capability**: No state persistence for partial downloads.

6.  **GET /api/peers endpoint**: Service method exists but no controller endpoint.
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
