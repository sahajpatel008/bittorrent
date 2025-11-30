# PEX Protocol Testing Guide

This guide explains how to test the **fully implemented PEX (Peer Exchange)** protocol.

## What's Implemented

✅ **Automatic periodic PEX updates** - Every 30 seconds per connection  
✅ **Event-driven PEX broadcasts** - When new peers are discovered  
✅ **Peer discovery via PEX** - Peers share addresses with each other  
✅ **Dropped peer tracking** - Disconnected peers are marked and shared  
✅ **Self-exclusion** - Own address and current peer excluded from updates  
✅ **Rate limiting** - Prevents duplicate updates within 60 seconds  

## Quick Test (Automated)

Run the automated test script:

```bash
cd "/Users/manavvakharia/Main/Data - Dell/SCU/Courses/CSEN317 - Distributed Systems/Project/bittorrent"
./test_pex.sh
```

This script will:
1. Build the project
2. Create a test file and torrent
3. Start tracker, seeder, and two leechers
4. Verify PEX is working
5. Analyze logs for PEX activity

## Manual Testing (Step-by-Step)

### Prerequisites

- Java 21+
- Maven
- 4 terminal windows
- Project built: `mvn clean package -DskipTests`

### Step 1: Create Test File and Torrent

```bash
cd "/Users/manavvakharia/Main/Data - Dell/SCU/Courses/CSEN317 - Distributed Systems/Project/bittorrent"

# Create test file
echo "Hello PEX World! Testing Peer Exchange protocol." > pex_test.txt

# Create torrent
java -jar target/java_bittorrent.jar create_torrent pex_test.txt pex_test.torrent
```

### Step 2: Start Tracker (Terminal 1)

```bash
cd "/Users/manavvakharia/Main/Data - Dell/SCU/Courses/CSEN317 - Distributed Systems/Project/bittorrent"
java -Djava.net.preferIPv4Stack=true -cp target/java_bittorrent.jar bittorrent.tracker.server.TrackerServerApplication
```

**Expected output:**
```
Started TrackerServerApplication in X.XXX seconds
```

### Step 3: Start Seeder (Terminal 2)

```bash
cd "/Users/manavvakharia/Main/Data - Dell/SCU/Courses/CSEN317 - Distributed Systems/Project/bittorrent"
java -Djava.net.preferIPv4Stack=true \
     -Dbittorrent.listen-port=6882 \
     -jar target/java_bittorrent.jar seed pex_test.torrent pex_test.txt
```

**Expected output:**
```
PeerServer listening on port 6882
Registered torrent for seeding: <infoHash>
Announced to tracker on port 6882. Seeding...
```

**Look for:**
- `extension: Message$Extension[...]` - Extension negotiation
- `pexExtensionId` being set (if DEBUG enabled)

### Step 4: First Leecher - Peer A (Terminal 3)

```bash
cd "/Users/manavvakharia/Main/Data - Dell/SCU/Courses/CSEN317 - Distributed Systems/Project/bittorrent"
java -Djava.net.preferIPv4Stack=true \
     -Dbittorrent.listen-port=6883 \
     -jar target/java_bittorrent.jar download -o pex_download_A.txt pex_test.torrent
```

**Expected output:**
```
PeerServer listening on port 6883
Attempting to pass tracker url: http://localhost:8080/announce
AnnounceResponse: {interval=1800, peers=[/127.0.0.1:6882]}
peer: trying to connect: /127.0.0.1:6882
RECV_LOOP: Bitfield[values.length=...]
...
```

**Look for PEX activity:**
- `SwarmManager[<infoHash>]: tracker added X peers` - Peers from tracker
- `extension: Message$Extension[...]` - PEX extension negotiation
- `Peer[<address>]: sent PEX update (added: X, dropped: Y)` - Periodic PEX updates (every 30 seconds)
- `SwarmManager[<infoHash>]: PEX added X peers` - Peers discovered via PEX

### Step 5: Second Leecher - Peer C (Terminal 4)

**Wait 10-15 seconds** after Peer A completes to allow PEX updates to propagate, then:

```bash
cd "/Users/manavvakharia/Main/Data - Dell/SCU/Courses/CSEN317 - Distributed Systems/Project/bittorrent"
java -Djava.net.preferIPv4Stack=true \
     -Dbittorrent.listen-port=6884 \
     -jar target/java_bittorrent.jar download -o pex_download_C.txt pex_test.torrent
```

**Expected output:**
```
PeerServer listening on port 6884
Attempting to pass tracker url: http://localhost:8080/announce
AnnounceResponse: {interval=1800, peers=[/127.0.0.1:6882, /127.0.0.1:6883]}
peer: trying to connect: /127.0.0.1:6882
...
```

**Look for PEX activity:**
- `SwarmManager[<infoHash>]: PEX added X peers` - Peer C discovering peers via PEX
- `PeerConnectionManager[<infoHash>]: broadcasting PEX to X peers` - Broadcasting new peers
- Multiple peer connections (should see connections to both seeder and Peer A)

### Step 6: Verify Downloads

```bash
# Verify Peer A download
diff pex_test.txt pex_download_A.txt
# Should show no differences

# Verify Peer C download
diff pex_test.txt pex_download_C.txt
# Should show no differences
```

## What to Look For in Logs

### PEX Extension Negotiation

```
extension: Message$Extension[id=0, content=...]
```

This shows the extension handshake where peers negotiate PEX support.

### Periodic PEX Updates

Every 30 seconds, you should see:
```
Peer[/127.0.0.1:6882]: sent PEX update (added: 2, dropped: 0)
```

This indicates automatic periodic PEX updates are working.

### PEX Peer Discovery

When peers are discovered via PEX:
```
SwarmManager[<infoHash>]: PEX added 2 peers
```

This shows peers learned about each other through PEX, not just the tracker.

### PEX Broadcasting

When new peers are discovered and broadcast:
```
PeerConnectionManager[<infoHash>]: broadcasting PEX to 2 peers (added: 1, dropped: 0)
```

This shows the system broadcasting new peer discoveries to all connected peers.

### Dropped Peers

When a peer disconnects:
```
Peer[/127.0.0.1:6883]: sent PEX update (added: 0, dropped: 1)
```

This shows dropped peers being communicated.

## Advanced Testing: Multiple Peers

To test with more peers:

1. Start tracker (Terminal 1)
2. Start seeder on port 6882 (Terminal 2)
3. Start leecher A on port 6883 (Terminal 3)
4. Wait 10 seconds
5. Start leecher B on port 6884 (Terminal 4)
6. Wait 10 seconds
7. Start leecher C on port 6885 (Terminal 5)

Each new peer should:
- Initially connect via tracker
- Receive PEX updates from connected peers
- Discover additional peers via PEX
- Broadcast its own peer list to others

## Troubleshooting

### No PEX Updates Visible

- **Check extension negotiation**: Look for `extension: Message$Extension` in logs
- **Verify PEX extension ID**: Should see `pexExtensionId` being set
- **Wait longer**: Periodic updates happen every 30 seconds
- **Check DEBUG mode**: Ensure `BitTorrentApplication.DEBUG = true`

### Peers Not Discovering Each Other

- **Check SwarmManager logs**: Look for `SwarmManager[<infoHash>]: PEX added X peers`
- **Verify connections**: Ensure peers are actually connected (check `peer: trying to connect`)
- **Check port conflicts**: Ensure each peer uses a different port

### PEX Updates Not Being Sent

- **Check if PEX is negotiated**: `pexExtensionId` should be >= 0
- **Verify connection is active**: Socket should not be closed
- **Check rate limiting**: Same peer won't be sent within 60 seconds

## Expected Behavior Summary

1. **Initial Connection**: Peers connect via tracker (bootstrap)
2. **Extension Negotiation**: PEX extension is negotiated during handshake
3. **Periodic Updates**: Every 30 seconds, peers send PEX updates
4. **Event-Driven Updates**: When new peers discovered, they're broadcast immediately
5. **Peer Discovery**: Peers learn about each other through PEX
6. **Dropped Peers**: Disconnected peers are marked and shared

## Success Criteria

✅ Peers negotiate PEX extension  
✅ Periodic PEX updates are sent (every 30 seconds)  
✅ Peers discover each other via PEX (not just tracker)  
✅ New peers are broadcast to connected peers  
✅ Dropped peers are tracked and shared  
✅ Multiple peers can connect and share peer lists  

If all criteria are met, PEX is working correctly!

