# BitTorrent PEX + Tracker Testing Guide

This guide explains how to exercise the **PEX (Peer Exchange)** plumbing you added on top of the existing **tracker‑based** swarm, and what logs to look at to confirm that:

- Tracker is used for **bootstrap / refresh**.
- PEX is negotiated between peers and can be used to share extra peer addresses.

> Note: The current code negotiates the `ut_pex` extension and can **receive / apply** PEX messages via `SwarmManager`, and it can **send** PEX when `Peer.sendPexUpdate()` is called. There is no automatic timer yet, so PEX messages will only be sent if you explicitly call that method (or wire it into your own scheduling logic).

---

## 1. Prerequisites

- Java 21+
- Maven
- 4+ terminal windows
- This repository built successfully (see below)

All commands assume you are in the project root:

```bash
cd /Users/manavvakharia/Main/Data\ -\ Dell/SCU/Courses/CSEN317\ -\ Distributed\ Systems/Project/bittorrent
```

---

## 2. Build the Project

```bash
mvn clean package -DskipTests
```

This produces `target/java_bittorrent.jar`.

---

## 3. Create a Test Torrent

Use a small text file (fast to download, easy to diff).

```bash
echo "Hello PEX World!" > pex_test.txt

java -jar target/java_bittorrent.jar create_torrent pex_test.txt pex_test.torrent
```

This creates `pex_test.torrent` pointing to `http://localhost:8080/announce`.

---

## 4. Start the Tracker (Terminal 1)

```bash
cd "/Users/manavvakharia/Main/Data - Dell/SCU/Courses/CSEN317 - Distributed Systems/Project/bittorrent"
java -Djava.net.preferIPv4Stack=true -cp target/java_bittorrent.jar bittorrent.tracker.server.TrackerServerApplication
```

Expected log lines:

- `Started TrackerServerApplication ...`
- Lines like:

  ```text
  Tracker announce: infoHash=<...> ip=127.0.0.1:6882 left=... downloaded=... uploaded=...
  ```

These show which peers have registered for a given info‑hash.

You can also inspect the persistent swarm data:

```bash
cat tracker_data/swarms.json
```

---

## 5. Start the Seeder (Peer B, Terminal 2)

```bash
cd "/Users/manavvakharia/Main/Data - Dell/SCU/Courses/CSEN317 - Distributed Systems/Project/bittorrent"
java -Djava.net.preferIPv4Stack=true -Dbittorrent.listen-port=6882 -jar target/java_bittorrent.jar seed pex_test.torrent pex_test.txt
```

Expected log excerpts:

```text
PeerServer listening on port 6882
Registered pex_test.txt for seeding.
Attempting to pass tracker url : http://localhost:8080/announce
AnnounceResponse: {interval=1800, min interval=300, peers=}
[]
Announced to tracker on port 6882. Seeding...
```

At this point, the tracker knows about the seeder, and the seeder is listening for incoming connections.

---

## 6. First Leecher (Peer A, Terminal 3)

Peer A will:

- Bootstrap from the tracker.
- Download the file (single-peer for now).
- Register with tracker as a seeder when complete.

```bash
cd "/Users/manavvakharia/Main/Data - Dell/SCU/Courses/CSEN317 - Distributed Systems/Project/bittorrent"
java -Djava.net.preferIPv4Stack=true -Dbittorrent.listen-port=6883 -jar target/java_bittorrent.jar download -o pex_download_A.txt pex_test.torrent
```

Key things to look for in Peer A logs:

- Tracker / peers:

  ```text
  Attempting to pass tracker url : http://localhost:8080/announce
  AnnounceResponse: {interval=1800, min interval=300, peers=...}
  [ /127.0.0.1:6882 ]
  ```

- Peer connection:

  ```text
  peer: trying to connect: /127.0.0.1:6882
  RECV_LOOP: Bitfield[values.length=...]
  RECV_LOOP: Piece[index=0, begin=0, block.length=...]
  ```

- Extension negotiation (if `BitTorrentApplication.DEBUG = true`):

  ```text
  extension: Message$Extension[id=0, content=...]
  ```

After completion, verify content:

```bash
diff pex_test.txt pex_download_A.txt
```

They should match.

---

## 7. Second Leecher (Peer C, Terminal 4)

Now start a second leecher that will share the swarm with Peer A:

```bash
cd "/Users/manavvakharia/Main/Data - Dell/SCU/Courses/CSEN317 - Distributed Systems/Project/bittorrent"
java -Djava.net.preferIPv4Stack=true -Dbittorrent.listen-port=6884 -jar target/java_bittorrent.jar download -o pex_download_C.txt pex_test.torrent
```

Tracker logs should now show **three** announces for the same info‑hash:

- Seeder @ 6882
- Leecher A @ 6883
- Leecher C @ 6884

You should see multiple tracker announces in the tracker terminal:

```text
Tracker announce: infoHash=<...> ip=127.0.0.1:6882 ...
Tracker announce: infoHash=<...> ip=127.0.0.1:6883 ...
Tracker announce: infoHash=<...> ip=127.0.0.1:6884 ...
```

On any peer terminal with `DEBUG` enabled, you should also see:

- Swarm manager logs when peers are registered from the tracker:

  ```text
  SwarmManager[<infoHash>]: tracker added N peers
  ```

- Multiple `peer: trying to connect: /127.0.0.1:...` lines as the multi‑peer scheduler opens connections.

Again, verify:

```bash
diff pex_test.txt pex_download_C.txt
```

---

## 8. Observing PEX Behavior

### 8.1. What is already wired

The following behaviors are already implemented:

- **Extension negotiation**:
  - Peers advertise `ut_metadata` and `ut_pex` in a handshake message.
  - Remote IDs are recorded in `Peer.metadataExtensionId` and `Peer.pexExtensionId`.
  - Per‑connection extension IDs are stored in `MessageSerialContext`.
- **PEX reception**:
  - If a peer sends a PEX message (extension ID == `pexExtensionId`), it is decoded by `PexMessageSerial`.
  - The `added` addresses are passed to `SwarmManager.onPexPeersDiscovered(infoHashHex, added)`.
  - You will see logs like:

    ```text
    SwarmManager[<infoHash>]: PEX added X peers
    ```

### 8.2. Sending PEX updates (optional wiring)

By default, the client **does not yet schedule PEX sends automatically**. The helper is already there:

```startLine:endLine:src/main/java/bittorrent/peer/Peer.java
	public void sendPexUpdate() throws IOException {
		if (pexExtensionId < 0) {
			return;
		}

		var swarm = SwarmManager.getInstance();
		// Share current known peers as "added".
		var known = swarm.acquirePeers(infoHashHex, 50);
		if (known.isEmpty()) {
			return;
		}

		var pex = new PexMessage.Pex(known, java.util.Collections.emptyList());

		send(new Message.Extension(
			(byte) pexExtensionId,
			pex
		));
	}
```

To **actively test PEX exchange between your own peers**, you can:

1. Add a one‑off call to `peer.sendPexUpdate()` after connections are established (for example, after a piece download starts), or schedule it on a timer.
2. Rebuild and rerun the steps above.
3. Look for:
   - `SwarmManager[<infoHash>]: PEX added X peers` in other peers' logs.
   - `AnnounceResponse` on later downloads showing fewer tracker calls because more peers are already known in `SwarmManager`.

---

## 9. Summary: Tracker + PEX Roles in This Client

- **Tracker**:
  - Used for initial bootstrap when there are too few known peers.
  - Can be called again when the swarm is sparse (see `downloadFile()`).

- **PEX**:
  - Negotiated via `ut_pex` in the extension handshake.
  - When enabled and wired to send updates, lets peers **share additional peer addresses** with each other.
  - Discovered peers are merged into `SwarmManager` and can be used by the multi‑peer scheduler for future downloads.

Use this guide as a base to evolve the client into a fully PEX‑driven swarm where the tracker is only contacted occasionally for refresh. 


