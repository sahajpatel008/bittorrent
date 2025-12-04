# BitTorrent Client - Setup and Installation Guide

A hybrid peer-to-peer file distribution system based on the BitTorrent protocol, implementing distributed algorithms including gossiping protocols, resource discovery, and resource allocation.

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Project Overview](#project-overview)
3. [Installation Steps](#installation-steps)
4. [Building the Project](#building-the-project)
5. [Running the System](#running-the-system)
6. [Using the System](#using-the-system)
7. [Configuration](#configuration)
8. [Troubleshooting](#troubleshooting)

---

## Prerequisites

Before setting up the project, ensure you have the following installed:

### Required Software

1. **Java Development Kit (JDK) 21 or higher**
   - Check installation: `java -version`
   - Download: [Oracle JDK](https://www.oracle.com/java/technologies/downloads/) or [OpenJDK](https://openjdk.org/)
   - **Note**: Java 21 is required. Java 17 or lower will not work.

2. **Apache Maven 3.6+**
   - Check installation: `mvn -version`
   - Download: [Maven Download](https://maven.apache.org/download.cgi)
   - Installation guide: [Maven Installation](https://maven.apache.org/install.html)

3. **Node.js 16+ and npm**
   - Check installation: `node -v` and `npm -v`
   - Download: [Node.js Download](https://nodejs.org/)
   - **Note**: npm comes bundled with Node.js

4. **Git** (optional, for cloning the repository)
   - Check installation: `git --version`
   - Download: [Git Download](https://git-scm.com/downloads)

### System Requirements

- **Operating System**: Linux, macOS, or Windows (with WSL recommended for Windows)
- **Memory**: Minimum 2GB RAM (4GB+ recommended)
- **Disk Space**: At least 500MB free space
- **Network**: Internet connection for downloading dependencies

### Command Line Tools

- **Bash shell** (for running scripts)
  - Linux/macOS: Built-in
  - Windows: Use Git Bash or WSL
- **Port checking tools**: `lsof` (macOS/Linux) or `netstat` (Windows/Linux)

---

## Project Overview

### Architecture

The system consists of three main components:

1. **Tracker Server**: Centralized peer discovery service (runs on port 8080)
2. **BitTorrent Client/Peer**: Peer-to-peer file sharing application
   - Backend API (Spring Boot, runs on port 8081+)
   - Frontend UI (React/Vite, runs on port 5173+)
   - Peer Server (BitTorrent protocol, runs on port 6881+)
3. **Storage**: Persistent state and downloaded files

### Project Structure

```
bittorrent/
├── src/main/java/bittorrent/    # Java source code
├── src/main/resources/          # Configuration files
├── react-frontend/              # React frontend application
├── scripts/                     # Startup/shutdown scripts
├── logs/                        # Application logs
├── tracker_data/                # Tracker persistent data
├── pom.xml                      # Maven build configuration
└── README.md                    # This file
```

---

## Installation Steps

### Step 1: Clone or Download the Project

If using Git:
```bash
git clone https://github.com/sahajpatel008/bittorrent.git
cd bittorrent
```

Or download and extract the project ZIP file to your desired location.

### Step 2: Verify Prerequisites

Run these commands to verify all prerequisites are installed:

```bash
# Check Java version (must be 21 or higher)
java -version

# Check Maven
mvn -version

# Check Node.js and npm
node -v
npm -v
```

**Expected Output:**
```
java version "21.0.x"  # or higher
Apache Maven 3.9.x     # or higher
v18.x.x or higher      # for Node.js
```

### Step 3: Build the Java Backend

Navigate to the project root directory and build the project:

```bash
cd /path/to/bittorrent
mvn clean install
```

**What this does:**
- Downloads all Maven dependencies (first time may take 5-10 minutes)
- Compiles all Java source files
- Runs tests (if any)
- Packages the application into a JAR file

**Expected Output:**
```
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
```

**Troubleshooting:**
- If build fails, check Java version: `java -version` must show 21+
- If Maven can't download dependencies, check internet connection
- If you see "JAVA_HOME not set", set it to your JDK installation path

### Step 4: Install Frontend Dependencies

Navigate to the frontend directory and install npm packages:

```bash
cd react-frontend
npm install
```

**What this does:**
- Downloads all Node.js dependencies (React, Vite, etc.)
- Creates `node_modules/` directory
- First time may take 2-5 minutes

**Expected Output:**
```
added 500+ packages, and audited 500+ packages in 30s
```

**Troubleshooting:**
- If `npm install` fails, try: `rm -rf node_modules package-lock.json && npm install`
- On macOS with Apple Silicon, you may need to reinstall if you see rollup errors

### Step 5: Create Required Directories

The application will create these automatically, but you can create them manually:

```bash
mkdir -p logs
mkdir -p tracker_data
```

---

## Building the Project

### Build Options

#### Option 1: Quick Build (Recommended for Development)

```bash
mvn clean compile
```

Compiles the code without packaging. Faster for development.

#### Option 2: Full Build with Packaging

```bash
mvn clean package
```

Creates executable JAR file in `target/java_bittorrent.jar`

#### Option 3: Spring Boot Build

```bash
mvn spring-boot:run
```

Compiles and runs the application directly (for BitTorrent client).

### Build Output

After successful build:
- Compiled classes: `target/classes/`
- JAR file: `target/java_bittorrent.jar` (if using `package`)
- Dependencies: Downloaded to `~/.m2/repository/` (Maven local repository)

---

## Running the System

You'll need **5 terminal windows** to run the complete system (tracker + 2 peers with frontends). Each component runs in its own terminal.

### Step 1: Start the Tracker Server (Terminal 1)

Navigate to the project root and start the tracker:

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

**Verify it's running:**
```bash
# In another terminal, test the tracker
curl http://localhost:8080/api/tracker/health
```

**Keep this terminal open** - the tracker must stay running.

---

### Step 2: Start Peer 1 - Seeder Backend (Terminal 2)

In a **new terminal**, start the first peer's backend:

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

**Keep this terminal open** - the backend must stay running.

---

### Step 3: Start Peer 1 Frontend (Terminal 3)

In a **new terminal**, start the first peer's frontend:

```bash
cd /path/to/bittorrent/react-frontend
npm run dev -- --port 5173
```

**Expected output:**
```
VITE v5.x.x  ready in XXX ms

➜  Local:   http://localhost:5173/
```

**Access the web UI:** Open `http://localhost:5173` in your browser.

**Keep this terminal open** - the frontend must stay running.

---

### Step 4: Start Peer 2 - Leecher Backend (Terminal 4)

In a **new terminal**, start the second peer's backend:

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

**Keep this terminal open** - the backend must stay running.

---

### Step 5: Start Peer 2 Frontend (Terminal 5)

In a **new terminal**, start the second peer's frontend:

```bash
cd /path/to/bittorrent/react-frontend
VITE_API_BASE=http://localhost:8082/api npm run dev -- --port 5174
```

**Expected output:**
```
VITE v5.x.x  ready in XXX ms

➜  Local:   http://localhost:5174/
```

**Access the web UI:** Open `http://localhost:5174` in your browser.

**Keep this terminal open** - the frontend must stay running.

---

### Port Summary

| Component | Port | Purpose |
|-----------|------|---------|
| **Tracker Server** | 8080 | HTTP announce endpoint & swarm management |
| **Peer 1 Backend** | 8081 | REST API for Peer 1 |
| **Peer 1 BitTorrent** | 6881 | Peer-to-peer file transfer |
| **Peer 1 Frontend** | 5173 | Web UI for Peer 1 |
| **Peer 2 Backend** | 8082 | REST API for Peer 2 |
| **Peer 2 BitTorrent** | 6882 | Peer-to-peer file transfer |
| **Peer 2 Frontend** | 5174 | Web UI for Peer 2 |

### Starting Additional Peers

To start Peer 3, use ports 8083 (backend), 6883 (BitTorrent), and 5175 (frontend):

```bash
# Peer 3 Backend (Terminal 6)
mvn spring-boot:run -Dspring-boot.run.main-class=bittorrent.BitTorrentApplication -Dspring-boot.run.arguments="--server.port=8083 --bittorrent.listen-port=6883 --bittorrent.peer-id=11223344556677889900"

# Peer 3 Frontend (Terminal 7)
cd react-frontend
VITE_API_BASE=http://localhost:8083/api npm run dev -- --port 5175
```

### Stopping the System

To stop components, press `Ctrl+C` in each terminal window where they're running.

**Order of shutdown:**
1. Stop all peer frontends (Ctrl+C in frontend terminals)
2. Stop all peer backends (Ctrl+C in backend terminals)
3. Stop tracker (Ctrl+C in tracker terminal)

**Note:** Each peer uses isolated storage: `peer_data/{port}/` (in the project repository)

---

## Using the System

### Access the Web Interface

Once a peer is running, open your browser:

```
http://localhost:5173
```

(Or the port shown when you started the peer)

### Basic Workflow

#### 1. Start All Components

Follow the steps in [Running the System](#running-the-system) to start:
- Tracker (Terminal 1)
- Peer 1 Backend (Terminal 2)
- Peer 1 Frontend (Terminal 3)
- Peer 2 Backend (Terminal 4)
- Peer 2 Frontend (Terminal 5)

#### 2. Seed a File (Peer 1 - Seeder)

1. Open `http://localhost:5173` in your browser (Peer 1)
2. Navigate to "Seeding Station" in the web UI
3. Upload a torrent file and the corresponding data file
4. Click "Start Seeding"
5. The file is now available for other peers to download
6. Verify seeder registered with tracker:
   ```bash
   curl "http://localhost:8080/api/tracker/swarms/<INFO_HASH>/peers"
   ```

#### 3. Download a File (Peer 2 - Leecher)

1. Open `http://localhost:5174` in your browser (Peer 2)
2. Navigate to "Download Manager" in the web UI
3. Upload the same torrent file that Peer 1 is seeding
4. Click "Start Download"
5. Monitor progress in "Active Transfers"
6. The download will:
   - Announce to tracker
   - Get Peer 1's address from tracker
   - Connect to Peer 1 on port 6881
   - Download pieces in parallel
   - Verify each piece with SHA-1 hash

#### 4. Verify the Transfer

After download completes, verify the files match:

```bash
# Compare file hashes
md5sum ~/bittorrent-downloads/original_file.mp4 ~/bittorrent-downloads/downloaded_file.mp4
# Both should show the same hash
```

Check active torrents:
```bash
# Peer 1 (Seeder)
curl "http://localhost:8081/api/torrents"

# Peer 2 (Leecher)  
curl "http://localhost:8082/api/torrents"
```

### Using the REST API

The backend exposes a REST API at `http://localhost:8081/api/` (or your peer's backend port).

#### Example: Get Torrent Info

```bash
curl -X POST -F "file=@sample.torrent" \
     http://localhost:8081/api/torrents/info
```

#### Example: Start Download

```bash
curl -X POST -F "file=@sample.torrent" \
     http://localhost:8081/api/torrents/download
```

Response includes a `jobId` for tracking progress.

#### Example: Check Download Status

```bash
curl http://localhost:8081/api/torrents/download/{jobId}/status
```

See `docs/API_ENDPOINTS.md` for complete API documentation.

---

## Configuration

### Backend Configuration

Edit `src/main/resources/application.properties`:

```properties
# Server port (each peer needs different port)
server.port=8081

# BitTorrent peer ID (20 characters)
bittorrent.peer-id=42112233445566778899

# Peer listening port (for incoming connections)
bittorrent.listen-port=6881

# Download directory
bittorrent.download-dir=./downloads

# Max connections
bittorrent.max-connections=50
```

### Frontend Configuration

Edit `react-frontend/vite.config.js` to change proxy settings:

```javascript
proxy: {
  '/api': {
    target: 'http://localhost:8081',  // Backend port
    changeOrigin: true
  }
}
```

### Port Allocation

When using scripts, ports are allocated automatically:

| Peer # | Frontend | Backend | Peer Server |
|--------|----------|---------|-------------|
| 1      | 5173     | 8081    | 6881        |
| 2      | 5174     | 8082    | 6882        |
| 3      | 5175     | 8083    | 6883        |

Each peer uses isolated storage: `peer_data/{port}/` (in the project repository)

---

## Troubleshooting

### Common Issues

#### 1. Port Already in Use

**Error**: `Port XXXX is already in use` or `Address already in use`

**Solution**:
```bash
# Find process using the port
lsof -i :8080  # For tracker
lsof -i :8081  # For backend
lsof -i :6881  # For peer server

# Kill the process
kill -9 <PID>
```

**Alternative**: Use different ports by modifying the `--server.port` and `--bittorrent.listen-port` arguments in the Maven commands.

#### 2. Java Version Mismatch

**Error**: `Unsupported class file major version` or `java.lang.UnsupportedClassVersionError`

**Solution**:
- Ensure Java 21 is installed: `java -version`
- Set JAVA_HOME: `export JAVA_HOME=/path/to/jdk21`
- Verify Maven uses correct Java: `mvn -version` should show Java 21

#### 3. Maven Build Fails

**Error**: `Could not resolve dependencies`

**Solution**:
- Check internet connection
- Clear Maven cache: `rm -rf ~/.m2/repository`
- Rebuild: `mvn clean install -U` (force update)

#### 4. Frontend Dependencies Error

**Error**: `Cannot find module @rollup/rollup-darwin-arm64` or similar

**Solution**:
```bash
cd react-frontend
rm -rf node_modules package-lock.json
npm install
```

#### 5. Peer Can't Connect to Tracker

**Error**: `Tracker unavailable` or connection refused

**Solution**:
- Ensure tracker is running in Terminal 1:
  ```bash
  mvn spring-boot:run -Dspring-boot.run.main-class=bittorrent.tracker.server.TrackerServerApplication -Dspring-boot.run.arguments="--server.port=8080"
  ```
- Verify tracker is responding: `curl http://localhost:8080/api/tracker/health`
- Check that tracker terminal shows: `Started TrackerServerApplication`

#### 6. Downloads Not Starting

**Error**: No peers available

**Solution**:
- Ensure tracker is running
- Ensure at least one peer has the file (seeding)
- Check that torrent file matches the data file
- Verify peer connections in logs

#### 7. Permission Denied on Scripts

**Error**: `Permission denied` when running scripts

**Solution**:
```bash
chmod +x scripts/*.sh
```

#### 8. Windows-Specific Issues

**Problem**: Scripts don't work on Windows

**Solution**:
- Use Git Bash or WSL (Windows Subsystem for Linux)
- Or run Maven commands manually (see Manual Start section)
- For frontend: Use PowerShell or Command Prompt for `npm` commands

### Debugging Tips

#### Check Logs

```bash
# Tracker logs
tail -f logs/tracker.log

# Peer backend logs
tail -f logs/peer-1-backend.log

# Peer frontend logs
tail -f logs/peer-1-frontend.log
```

#### Verify Components

```bash
# Check if tracker is running
curl http://localhost:8080/api/tracker/health

# Check if backend is running
curl http://localhost:8081/api/

# List all running peers
./scripts/list-peers.sh
```

#### Test Network Connectivity

```bash
# Test tracker connection
curl -v http://localhost:8080/api/tracker/announce

# Test peer API
curl http://localhost:8081/api/torrents
```

---

## Project Features

### Implemented Features

✅ **Full BitTorrent Protocol**
- Peer wire protocol (handshake, messages, piece transfer)
- Tracker communication (HTTP)
- Piece verification (SHA-1 hashing)

✅ **Peer Exchange (PEX)**
- Decentralized peer discovery
- Gossiping protocol implementation
- Fault-tolerant when tracker unavailable

✅ **Bidirectional Communication**
- Download files from peers
- Seed files to peers
- True peer-to-peer operation

✅ **State Persistence**
- Automatic state saving
- Resume interrupted downloads
- Cross-restart recovery

✅ **REST API**
- Full REST API for programmatic access
- Real-time progress updates (SSE)
- Job tracking and management

✅ **Web Interface**
- React-based frontend
- Real-time progress monitoring
- Torrent analysis and creation tools

### Distributed Algorithms

1. **Gossiping Protocols**: PEX epidemic-style information propagation
2. **Resource Discovery**: Hybrid tracker + PEX discovery
3. **Resource Allocation**: Round-robin piece allocation

---

## File Locations

### Application Data

- **Downloaded Files**: `~/bittorrent-downloads/` (home directory)
- **Peer State**: `peer_data/{port}/` (in the project repository)
  - Torrents: `peer_data/{port}/torrents/`
  - Download Jobs: `peer_data/{port}/download_jobs.json`
  - Seeding State: `peer_data/{port}/seeding_torrents.json`
  - Known Peers: `peer_data/{port}/known_peers.json`
  - Seeding Files: `peer_data/{port}/seeding/{infoHash}/`

### Tracker Data

- **Swarm Data**: `tracker_data/swarms.json`

### Logs

- **Tracker**: `logs/tracker.log`
- **Peer Backend**: `logs/peer-{n}-backend.log`
- **Peer Frontend**: `logs/peer-{n}-frontend.log`

---

## Testing the System

### Basic Test Scenario

1. **Start Tracker** (Terminal 1)
   ```bash
   mvn spring-boot:run -Dspring-boot.run.main-class=bittorrent.tracker.server.TrackerServerApplication -Dspring-boot.run.arguments="--server.port=8080"
   ```

2. **Start Peer 1 (Seeder)** (Terminals 2 & 3)
   ```bash
   # Terminal 2 - Backend
   mvn spring-boot:run -Dspring-boot.run.main-class=bittorrent.BitTorrentApplication -Dspring-boot.run.arguments="--server.port=8081 --bittorrent.listen-port=6881 --bittorrent.peer-id=42112233445566778899"
   
   # Terminal 3 - Frontend
   cd react-frontend
   npm run dev -- --port 5173
   ```
   - Open `http://localhost:5173`
   - Upload torrent file and data file
   - Start seeding

3. **Start Peer 2 (Downloader)** (Terminals 4 & 5)
   ```bash
   # Terminal 4 - Backend
   mvn spring-boot:run -Dspring-boot.run.main-class=bittorrent.BitTorrentApplication -Dspring-boot.run.arguments="--server.port=8082 --bittorrent.listen-port=6882 --bittorrent.peer-id=99887766554433221100"
   
   # Terminal 5 - Frontend
   cd react-frontend
   VITE_API_BASE=http://localhost:8082/api npm run dev -- --port 5174
   ```
   - Open `http://localhost:5174`
   - Upload same torrent file
   - Start download
   - Monitor progress

4. **Verify Download**
   - Check download completes
   - Verify file integrity
   - Check that Peer 2 can now seed

### Testing PEX (Tracker Failure)

1. Start tracker and 2-3 peers using the commands above
2. Start a download between peers
3. Stop tracker: Press `Ctrl+C` in the tracker terminal
4. Verify download continues using PEX
5. Verify new peers are discovered via PEX

See `docs/TESTING.md` and `docs/PEX_TESTING_GUIDE.md` for detailed testing procedures.

---

## Development

### Project Structure

```
src/main/java/bittorrent/
├── controller/          # REST API endpoints
├── service/            # Business logic
├── peer/               # Peer protocol implementation
│   ├── protocol/       # Message types
│   ├── serial/         # Serialization
│   └── storage/        # State persistence
├── tracker/            # Tracker communication
├── torrent/            # Torrent data models
└── util/               # Utilities
```

### Building for Development

```bash
# Compile only (fast)
mvn compile

# Run with hot reload (if using IDE)
# Or use: mvn spring-boot:run
```

### Running Tests

```bash
mvn test
```

---

## Additional Resources

- **API Documentation**: `docs/API_ENDPOINTS.md`
- **Architecture Details**: `docs/CONTEXT.md`
- **Testing Guide**: `docs/TESTING.md`
- **PEX Testing**: `docs/PEX_TESTING_GUIDE.md`
- **Peer Management**: `docs/PEER_MANAGEMENT.md`

