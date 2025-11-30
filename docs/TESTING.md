# BitTorrent P2P Testing Guide

This document describes how to test the complete BitTorrent peer-to-peer file transfer flow using the tracker, seeder, and leecher components.

## Prerequisites

- Java 21+
- Maven
- 3 terminal windows

## Architecture Overview

```
┌─────────────────┐
│     Tracker     │
│   (Port 8080)   │
└────────┬────────┘
         │
    Announce/
    Peer Discovery
         │
    ┌────┴────┐
    │         │
    ▼         ▼
┌─────────┐  ┌─────────┐
│ Seeder  │  │ Leecher │
│ (6882)  │◄─┤ (6883)  │
└─────────┘  └─────────┘
    Peer-to-Peer
    File Transfer
```

## Step-by-Step Testing

### Step 1: Build the Project

```bash
cd /home/sahajpatel008/Sahaj/SCU/SCU_Academics/5_2025\ Fall/CSEN317_Distributed_systems/DS_project/bt_client_made/bittorrent-java
mvn clean package -DskipTests
```

### Step 2: Create a Test File

**Option A: Small text file**
```bash
echo "This is a test file for BitTorrent transfer testing" > testfile.txt
```

**Option B: Larger file (recommended for multi-piece testing)**
```bash
# Create a 1MB file with repeated content
yes "This is test content for BitTorrent transfer" | head -c 1048576 > testfile.txt
```

> **Note:** Default piece size is 256KB, so files smaller than that will only have 1 piece.

### Step 3: Start the Tracker (Terminal 1)

```bash
java -cp target/java_bittorrent.jar bittorrent.tracker.server.TrackerServerApplication
```

Expected output:
```
Started TrackerServerApplication in X.XXX seconds
```

The tracker runs on **port 8080** and handles:
- Peer registration
- Peer discovery
- Swarm management

### Step 4: Create a Torrent File

```bash
java -jar target/java_bittorrent.jar create_torrent testfile.txt test.torrent
```

This creates a `.torrent` file that:
- Contains file metadata (name, size, piece hashes)
- Points to the local tracker (`http://localhost:8080/announce`)

### Step 5: Start the Seeder (Terminal 2)

```bash
java -Dbittorrent.listen-port=6882 -jar target/java_bittorrent.jar seed test.torrent testfile.txt
```

Expected output:
```
PeerServer listening on port 6882
Registered torrent for seeding: <info_hash>
```

The seeder:
- Registers with the tracker
- Starts PeerServer to accept incoming connections
- Responds to piece requests from leechers

### Step 6: Download as Leecher (Terminal 3)

**Option A: Download complete file**
```bash
java -Dbittorrent.listen-port=6883 -jar target/java_bittorrent.jar download -o downloaded.txt test.torrent
```

**Option B: Download a single piece**
```bash
java -Dbittorrent.listen-port=6883 -jar target/java_bittorrent.jar download_piece -o piece0.bin test.torrent 0
```

### Step 7: Verify the Download

The downloaded file is saved to the path specified with the `-o` flag (e.g., `downloaded.txt`).

```bash
# View the downloaded file
cat downloaded.txt

# Compare with original
diff testfile.txt downloaded.txt

# Or verify using MD5 hash
md5sum testfile.txt downloaded.txt
```

If both files have the same hash, the transfer was successful!

---

## Understanding the Logs

### Seeder Logs (Terminal 2)

```
Accepted connection from /127.0.0.1:32886        # Incoming connection from leecher
Handshake successful with /127.0.0.1:32886       # Protocol handshake completed
send: typeId=5  message=Bitfield[values.length=1] # Sent bitfield (all pieces available)
RECV_LOOP: Interested[]                           # Leecher is interested
send: typeId=1  message=Unchoke[]                 # Allowed leecher to download
RECV_LOOP: Request[index=0, begin=0, length=52]   # Piece request received
send: typeId=7  message=Piece[...]                # Sent piece data
RECV_LOOP: Have[pieceIndex=0]                     # Leecher confirmed receipt
Peer connection closed: /127.0.0.1:32886          # Connection closed
```

### Leecher Logs (Terminal 3)

```
PeerServer listening on port 6883                 # Started own server
Attempting to pass tracker url: http://localhost:8080/announce
AnnounceResponse: {interval=1800, peers=...}      # Got peer list from tracker
Peer: trying to connect: /127.0.0.1:6882          # Connecting to seeder
RECV_LOOP: Bitfield[values.length=1]              # Received seeder's bitfield
send: typeId=2  message=Interested[]              # Expressed interest
send: typeId=6  message=Request[...]              # Requested piece
RECV_LOOP: Unchoke[]                              # Seeder allowed download
RECV_LOOP: Piece[index=0, begin=0, block.length=52] # Received piece data!
send: typeId=4  message=Have[pieceIndex=0]        # Announced piece completion
```

---

## Port Assignments

| Component | Port | Purpose |
|-----------|------|---------|
| Tracker   | 8080 | HTTP announce endpoint |
| Seeder    | 6882 | PeerServer for incoming connections |
| Leecher   | 6883 | PeerServer (becomes seeder after download) |

> **Note:** When the leecher connects TO the seeder, the OS assigns a random ephemeral port (e.g., 32886) for the outgoing connection. This is normal TCP behavior.

---

## Troubleshooting

### Port Already in Use

```bash
# Find process using the port
lsof -i :6881
# Kill if needed
kill -9 <PID>
```

### Tracker Not Responding

```bash
# Test tracker directly
curl "http://localhost:8080/announce?info_hash=test&peer_id=test&port=6881&uploaded=0&downloaded=0&left=100&compact=1"
```

### Check Registered Peers

```bash
# View tracker's stored data
cat tracker_data/swarms.json
```

### No Peers Found

- Ensure seeder started **before** leecher
- Check that seeder successfully registered with tracker
- Verify both using the same `.torrent` file

---

## Message Types Reference

| Type ID | Name       | Direction | Description |
|---------|------------|-----------|-------------|
| 0       | Choke      | S → L     | Stop sending pieces |
| 1       | Unchoke    | S → L     | Allow piece requests |
| 2       | Interested | L → S     | Want to download |
| 3       | NotInterested | L → S  | Don't need pieces |
| 4       | Have       | Both      | Announce piece completion |
| 5       | Bitfield   | S → L     | Available pieces bitmap |
| 6       | Request    | L → S     | Request piece data |
| 7       | Piece      | S → L     | Piece data response |
| 8       | Cancel     | L → S     | Cancel pending request |

---

## Quick Test Script

```bash
#!/bin/bash
# test_p2p.sh - Automated P2P test

JAR="target/java_bittorrent.jar"

echo "=== Building ==="
mvn clean package -DskipTests -q

echo "=== Creating test file ==="
echo "Hello BitTorrent World!" > testfile.txt

echo "=== Starting Tracker ==="
java -cp $JAR bittorrent.tracker.server.TrackerServerApplication &
TRACKER_PID=$!
sleep 2

echo "=== Creating torrent ==="
java -jar $JAR create_torrent testfile.txt test.torrent

echo "=== Starting Seeder ==="
java -Dbittorrent.listen-port=6882 -jar $JAR seed test.torrent testfile.txt &
SEEDER_PID=$!
sleep 2

echo "=== Downloading ==="
java -Dbittorrent.listen-port=6883 -jar $JAR download -o downloaded.txt test.torrent

echo "=== Verifying ==="
if diff -q testfile.txt downloaded.txt > /dev/null 2>&1; then
    echo "✓ SUCCESS: Files match!"
else
    echo "✗ FAILURE: Files differ"
fi

echo "=== Cleanup ==="
kill $SEEDER_PID $TRACKER_PID 2>/dev/null
rm -f testfile.txt test.torrent downloaded.txt
```
