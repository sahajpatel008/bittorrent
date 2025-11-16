# BitTorrent Client in Java (Spring Boot API)

This project is a BitTorrent client implemented in Java. It was originally a command-line tool that has been refactored into a Spring Boot web application, exposing its core functionality as a REST API.

The client can parse `.torrent` files and magnet links, communicate with trackers to find peers, and download complete files from those peers.

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
│       ├── Main.java                 # Deprecated CLI entry point
│       │
│       ├── controller/             # REST API definitions
│       │   └── BitTorrentController.java
│       │
│       ├── service/                # Core business logic
│       │   └── BitTorrentService.java
│       │
│       ├── bencode/                # Bencode (de)serializer classes
│       ├── magnet/                 # Magnet link parser
│       ├── peer/                   # Peer connection & wire protocol logic
│       ├── torrent/                # Data models for .torrent files
│       ├── tracker/                # Tracker HTTP client logic
│       └── util/                   # SHA-1 and Network utilities
│
├── .gitignore              # Git ignore file
├── sample.torrent          # Example torrent file for testing
└── ... (other config files)
```

## ⚙️ How to Run (Development)

You must have Java (JDK 21+) and Maven installed.

All commands should be run from the project's root directory (where `pom.xml` is located).

This method uses the Spring Boot plugin to compile and run the application in one step. It's the fastest way to get the server running.

```bash
mvn spring-boot:run
```

Once running, the server will be available at `http://localhost:8080`.

## 📖 API Endpoints

You can interact with the running application using these endpoints [cite: `BitTorrentController.java`](src/main/java/bittorrent/controller/BitTorrentController.java).

### GET /api/decode

Decodes a Bencoded string and returns it as JSON.

**Query Parameter**: `encoded` (string)

**Example**:

```bash
curl "http://localhost:8080/api/decode?encoded=d3:bar4:spam3:fooi42ee"
```

**Response**:

```json
{"bar":"spam","foo":42}
```

### POST /api/info

Parses a `.torrent` file and returns its metadata as a formatted string.

**Body**: `multipart/form-data`

**Form Key**: `file` (file)

**Example**:

```bash
curl -X POST -F "file=@/path/to/sample.torrent" http://localhost:8080/api/info
```

**Response**:

```
Tracker URL: http://...
Length: 123456
Info Hash: ...
Piece Length: ...
Piece Hashes:
...
```

### POST /api/download/piece/{pieceIndex}

Downloads a specific piece from a `.torrent` file and returns the raw binary data.

**Path Variable**: `pieceIndex` (int)

**Body**: `multipart/form-data`

**Form Key**: `file` (file)

**Example**:

```bash
# Downloads piece 0 and saves it as 'piece_0.bin'
curl -X POST -F "file=@/path/to/sample.torrent" \
     http://localhost:8080/api/download/piece/0 \
     --output piece_0.bin
```
Or you can also test with the actual torrent file:
```bash
curl -X POST -F "file=@big-buck-bunny.torrent" \
     http://localhost:8080/api/download/piece/0 \
     --output piece_0.bin
```


### POST /api/download

Downloads a complete file from a `.torrent` file.

**Body**: `multipart/form-data`

**Form Key**: `file` (file)

**Example**:

```bash
# Downloads the file specified in 'sample.torrent'
curl -X POST -F "file=@/path/to/sample.torrent" \
     http://localhost:8080/api/download \
     --output downloaded_file_name.ext
```
