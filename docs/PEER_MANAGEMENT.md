# Peer Management Scripts

Scripts to manage multiple peer instances on localhost with automatic port detection. These scripts automate the process of starting and stopping peer instances, handling port conflicts, and managing dependencies.

> **Note:** For detailed testing instructions with tracker and multiple peers, see [TESTING.md](./TESTING.md).

## Overview

Each peer instance consists of:
- **Frontend** (Vite/React): Starting from port 5173
- **Backend** (Spring Boot): Starting from port 8081
- **Peer Server**: Starting from port 6881

The scripts automatically detect running peers and start new instances on the next available ports.

## Scripts

### `scripts/start-peer.sh`

Starts a new peer instance with automatic port detection.

```bash
./scripts/start-peer.sh
# Or from project root:
scripts/start-peer.sh
```

**What it does:**
1. Checks which ports are in use (frontend 5173+, backend 8081+, peer 6881+)
2. Finds the next available peer number
3. Starts backend with appropriate ports using correct Maven main-class specification
4. Automatically installs/reinstalls frontend dependencies if needed (handles rollup native module issues)
5. Starts frontend with appropriate port and proxy configuration
6. Stores PIDs and peer info in `logs/` directory
7. Outputs log files: `logs/peer-{n}-frontend.log` and `logs/peer-{n}-backend.log`

**Example output:**
```
Starting Peer #1
  Frontend: http://localhost:5173
  Backend:  http://localhost:8081
  Peer:     localhost:6881
```

### `scripts/stop-peer.sh`

Stops a specific peer instance or all peers.

```bash
# Stop peer #1
./scripts/stop-peer.sh 1
# Or: scripts/stop-peer.sh 1

# Stop all peers
./scripts/stop-peer.sh all
# Or: scripts/stop-peer.sh all
```

**What it does:**
1. Reads PIDs from `logs/peer-{n}.pids`
2. Sends TERM signal for graceful shutdown
3. Sends KILL signal if process is still running
4. Cleans up PID and info files

### `scripts/list-peers.sh`

Lists all running peer instances with their status.

```bash
./scripts/list-peers.sh
# Or: scripts/list-peers.sh
```

**What it shows:**
- Peer number
- Frontend, backend, and peer ports
- Process status (Running/Partially Running/Stopped)
- PIDs
- Log file locations

## Logs

All logs are stored in the `logs/` directory:

- `logs/peer-{n}-frontend.log` - Frontend (Vite) logs
- `logs/peer-{n}-backend.log` - Backend (Spring Boot) logs
- `logs/peer-{n}-frontend-install.log` - Frontend dependency installation logs (if dependencies were reinstalled)
- `logs/peer-{n}.pids` - Process IDs (for stopping)
- `logs/peer-{n}.info` - Peer configuration (ports, PIDs)

The `logs/` directory is automatically created when you run `start-peer.sh` for the first time.

## Port Allocation

Ports are allocated sequentially:

| Peer # | Frontend | Backend | Peer Server |
|--------|----------|---------|-------------|
| 1      | 5173     | 8081    | 6881        |
| 2      | 5174     | 8082    | 6882        |
| 3      | 5175     | 8083    | 6883        |
| ...    | ...      | ...     | ...         |

## Storage Isolation

Each peer uses its own isolated storage directory based on the peer server port:
- Peer 1 (port 6881): `~/.bittorrent-peer-6881/`
- Peer 2 (port 6882): `~/.bittorrent-peer-6882/`
- etc.

This ensures peer data (known peers, torrents, download jobs) don't overlap.

## Requirements

- Java (JDK 21+)
- Maven
- Node.js and npm
- Bash shell
- `lsof` or `netstat` command (for port detection)

## Troubleshooting

### Frontend Dependencies Error (Rollup Native Module)

If you see an error like `Cannot find module @rollup/rollup-darwin-arm64`, the script will automatically:
1. Detect the missing module
2. Remove `package-lock.json` and `node_modules`
3. Reinstall dependencies
4. Verify the rollup module is installed

If the automatic fix doesn't work, manually fix it:
```bash
cd react-frontend
rm -rf node_modules package-lock.json
npm install
```

### Backend Main Class Error

If you see `Unable to find a single main class`, the script uses the correct Maven command with `-Dspring-boot.run.main-class=bittorrent.BitTorrentApplication`. This should be handled automatically.

### Port already in use

If a port is already in use by another application, the script will skip to the next available port. You may need to manually stop the conflicting process:
```bash
# Find process using a port
lsof -i :8081

# Kill by PID
kill -9 <PID>
```

### Processes not stopping

If `stop-peer.sh` doesn't work, you can manually kill processes:
```bash
# Find process using a port
lsof -i :8081

# Kill by PID
kill -9 <PID>
```

Alternatively, check the PID file:
```bash
cat logs/peer-1.pids
kill -9 <PID_FROM_FILE>
```

### Logs not appearing

Make sure the `logs/` directory exists and is writable:
```bash
mkdir -p logs
chmod 755 logs
```

### Frontend not starting

Check the frontend log for errors:
```bash
tail -f logs/peer-1-frontend.log
```

Common issues:
- Missing dependencies: The script should auto-fix this, but if not, see "Frontend Dependencies Error" above
- Port conflict: The script will use the next available port
- Proxy configuration: Ensure `VITE_BACKEND_PORT` matches the backend port

### Backend not starting

Check the backend log for errors:
```bash
tail -f logs/peer-1-backend.log
```

Common issues:
- Maven compilation errors: Run `mvn clean compile` manually
- Port conflict: The script will use the next available port
- Missing Java: Ensure JDK 21+ is installed and in PATH

## Technical Details

### Backend Startup

The backend is started using Maven with explicit main class specification:
```bash
mvn spring-boot:run \
  -Dspring-boot.run.main-class=bittorrent.BitTorrentApplication \
  -Dspring-boot.run.arguments="--server.port=$BACKEND_PORT --bittorrent.listen-port=$PEER_PORT"
```

This ensures the correct application class is used (not the tracker server).

### Frontend Startup

The frontend is started with environment variables:
- `PORT`: Frontend port (e.g., 5173, 5174, ...)
- `VITE_BACKEND_PORT`: Backend port for proxy configuration (e.g., 8081, 8082, ...)

The Vite proxy automatically forwards `/api` requests to the correct backend.

### Dependency Management

The script automatically handles frontend dependencies:
- Checks for `node_modules` directory
- Checks for `vite` binary
- Checks for `@rollup/rollup-darwin-arm64` native module
- Reinstalls if any are missing

## Example Workflow

```bash
# Start first peer
./scripts/start-peer.sh
# Output: Starting Peer #1 on ports 5173, 8081, 6881

# Start second peer
./scripts/start-peer.sh
# Output: Starting Peer #2 on ports 5174, 8082, 6882

# List running peers
./scripts/list-peers.sh

# Check logs
tail -f logs/peer-1-frontend.log
tail -f logs/peer-1-backend.log

# Stop peer #1
./scripts/stop-peer.sh 1

# Stop all peers
./scripts/stop-peer.sh all
```

## Integration with Tracker

To test with a tracker server, start the tracker first (see [TESTING.md](./TESTING.md)):

```bash
# Terminal 1: Start tracker
mvn spring-boot:run -Dspring-boot.run.main-class=bittorrent.tracker.server.TrackerServerApplication -Dspring-boot.run.arguments="--server.port=8080"

# Terminal 2: Start peer 1 (seeder)
./scripts/start-peer.sh

# Terminal 3: Start peer 2 (leecher)
./scripts/start-peer.sh
```

Each peer will automatically connect to the tracker on port 8080.

