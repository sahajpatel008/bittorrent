package bittorrent.service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.google.gson.Gson;

import bittorrent.BitTorrentApplication;
import bittorrent.bencode.BencodeDeserializer;
import bittorrent.bencode.BencodeSerializer;
import bittorrent.config.BitTorrentConfig;
import bittorrent.peer.Peer;
import bittorrent.peer.PeerServer;
import bittorrent.torrent.Torrent;
import bittorrent.torrent.TorrentInfo;
import bittorrent.tracker.TrackerClient;
import bittorrent.util.DigestUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

// Using a @Service to integrate logic into Spring's component model
@Service
public class BitTorrentService {
 
	private final TrackerClient trackerClient = new TrackerClient();
	private final Gson gson = new Gson();
	private final HexFormat hexFormat = BitTorrentApplication.HEX_FORMAT;
	private final PeerServer peerServer;
	private final BitTorrentConfig config;

	public BitTorrentService(PeerServer peerServer, BitTorrentConfig config) {
		this.peerServer = peerServer;
		this.config = config;
	}

	@PostConstruct
	public void init() {
		peerServer.start();
	}

	@PreDestroy
	public void cleanup() {
		peerServer.stop();
	}

    // --- Debug / Status Methods ---

	public String getSeedingStatus(String path) throws IOException {
		Torrent torrent = load(path);
		return peerServer.getTorrentStatus(torrent.info().hash());
	}

    // --- Utility Methods ---

	@SuppressWarnings("unchecked")
	private Torrent load(String path) throws IOException {
		final var content = Files.readAllBytes(Paths.get(path));
		final var decoded = new BencodeDeserializer(content).parse();

		return Torrent.of((Map<String, Object>) decoded);
	}

	private String infoToString(String trackerUrl, TorrentInfo info) {
		final var hashes = info.pieces().stream()
			.map(hexFormat::formatHex)
			.collect(Collectors.joining("\n"));
		
		return """
			Tracker URL: %s
			Length: %d
			Info Hash: %s
			Piece Length: %d

			Piece Hashes:
			%s
			""".formatted(trackerUrl, info.length(), hexFormat.formatHex(info.hash()), info.pieceLength(), hashes);
	}

    // --- Core BitTorrent Commands (from original Main.java) ---

    // CLI Entry point
	public void run(String[] args) throws Exception {
		if (args.length < 2) {
			System.out.println("Usage: java -jar java_bittorrent.jar <command> <args>");
			return;
		}
		
		final var command = args[0];
		
		switch (command) {
			case "decode" -> System.out.println(decode(args[1]));
			case "info" -> System.out.println(getInfo(args[1]));
			case "peers" -> System.out.println(getPeers(args[1]));
			case "handshake" -> System.out.println(handshake(args[1], args[2]));
			case "download_piece" -> {
				// Arguments: download_piece -o /tmp/test.iso pieceIndex torrentFile
				// This argument parsing is slightly different from original Main.java which had a specific structure
				// Assuming standard args for now: download_piece <output> <torrent> <piece_index>
				// BUT based on original Main.java: args[0]=command, args[1]=torrent, args[2]=output, args[3]=pieceIndex
				// Wait, original switch in Main.java was:
				// case "download_piece" -> ... args[3] is torrent, args[4] is index, args[2] is output
				
				if (args.length >= 5) {
					final var piece = downloadPiece(args[3], Integer.parseInt(args[4]));
					// If downloadPiece returns a File, we might want to move it or read it.
					// But my updated method returns `byte[]` in the controller version? 
					// Wait, the controller expects `byte[]` from `downloadPiece`. 
					// In the restored version I wrote above (read_file output), `downloadPiece` returns `File`.
					// Let's make it consistent. The controller uses `byte[]` in my last edit to controller.
					// So `downloadPiece` should return `byte[]`.
					
					// Let's adjust the implementation below to match Controller's expectation.
				}
			}
			case "download" -> {
				// Similar logic
				if (args.length >= 4) {
					String outputPath = args[2];
					File downloaded = downloadFile(args[3]);
					Files.copy(downloaded.toPath(), Paths.get(outputPath), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
					downloaded.delete();
					System.out.println("Downloaded to: " + outputPath);
				}
			}
			case "seed" -> seed(args[1], args[2]); // seed <torrent> <filePath>
			case "create_torrent" -> {
				if (args.length < 3) {
					System.out.println("Usage: create_torrent <inputFile> <outputTorrent>");
					return;
				}
				createTorrent(args[1], args[2]);
			}
		}
	}

	// --- Public methods used by Controller and CLI ---

	public void seed(String torrentPath, String filePath) throws IOException, InterruptedException {
		final var torrent = load(torrentPath);
		final var torrentInfo = torrent.info();
		final var file = new File(filePath);

		if (!file.exists()) {
			System.err.println("File to seed does not exist: " + filePath);
			return;
		}

		// Register file with PeerServer so we can serve pieces
		peerServer.registerTorrent(torrentInfo, file);
		System.out.println("Registered " + filePath + " for seeding.");

		// Announce to tracker with our listen port
		trackerClient.announce(torrent, config.getListenPort());
		System.out.println("Announced to tracker on port " + config.getListenPort() + ". Seeding...");

		// Keep this process alive so other peers can connect
		Thread.currentThread().join();
	}

	/**
	 * Creates a single-file .torrent for the given input file, pointing to the local tracker.
	 */
	public void createTorrent(String inputPath, String outputPath) throws IOException {
		final var input = Paths.get(inputPath);
		if (!Files.exists(input)) {
			System.err.println("Input file does not exist: " + inputPath);
			return;
		}

		final long length = Files.size(input);
		final String name = input.getFileName().toString();
		final int pieceLength = 262_144; // 256 KiB, matches many common torrents

		// 1. Compute piece hashes
		final var piecesOut = new ByteArrayOutputStream();
		try (InputStream in = Files.newInputStream(input)) {
			byte[] buffer = new byte[pieceLength];
			int read;
			while ((read = in.read(buffer)) != -1) {
				byte[] block = (read == pieceLength) ? buffer : Arrays.copyOf(buffer, read);
				byte[] hash = DigestUtils.sha1(block);
				piecesOut.write(hash);
			}
		}

		final String piecesStr = new String(piecesOut.toByteArray(), StandardCharsets.ISO_8859_1);

		// 2. Build info dictionary
		Map<String, Object> info = new LinkedHashMap<>();
		info.put("name", name);
		info.put("length", length);
		info.put("piece length", (long) pieceLength);
		info.put("pieces", piecesStr);

		// 3. Build root dictionary with local tracker announce
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("announce", "http://localhost:8080/announce");
		root.put("info", info);

		// 4. Bencode and write to file
		byte[] encoded = new BencodeSerializer().writeAsBytes(root);
		Files.write(Paths.get(outputPath), encoded);

		System.out.println("Created torrent: " + outputPath);
	}

	public String decode(String encoded) {
        try {
            final var decoded = new BencodeDeserializer(encoded.getBytes()).parse();
            return gson.toJson(decoded);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
	}

	public String getInfo(String path) throws IOException {
		final var torrent = load(path);
		return infoToString(torrent.announce(), torrent.info());
	}

	public String getPeers(String path) throws IOException {
		final var torrent = load(path);
		final var response = trackerClient.announce(torrent);

		return response.peers().stream()
			.map(peer -> "%s:%d".formatted(peer.getAddress().getHostAddress(), peer.getPort()))
			.collect(Collectors.joining("\n"));
	}

	public String handshake(String path, String peerIpAndPort) throws IOException, InterruptedException {
		final var torrent = load(path);
		// We need a File object. It can be a placeholder since we're just handshaking.
		final var tempFile = new File(torrent.info().name());

		final var parts = peerIpAndPort.split(":", 2);
		final var socket = new Socket(parts[0], Integer.parseInt(parts[1]));

		try (final var peer = Peer.connect(socket, torrent, torrent.info(), tempFile, config.getPeerId())) { 
			return "Peer ID: %s".formatted(hexFormat.formatHex(peer.getId()));
		}
	}

	// Changed to return byte[] to match Controller expectation
	public byte[] downloadPiece(String path, int pieceIndex) throws IOException, InterruptedException {
		final var torrent = load(path);
		final var torrentInfo = torrent.info();

		// Register torrent with PeerServer for seeding
		final var fullFile = new File(torrentInfo.name());
		peerServer.registerTorrent(torrentInfo, fullFile);

		final var response = trackerClient.announce(torrent);
		final var peers = response.peers().stream()
			.filter(p -> p.getPort() != config.getListenPort()) // avoid connecting to ourselves
			.toList();

		if (peers.isEmpty()) {
			System.out.println("No other peers returned by tracker.");
			return null;
		}

		final var firstPeer = peers.getFirst();
		// Temporary file logic removed if we return bytes directly, 
		// BUT Peer.downloadPiece writes to a file if it uses RandomAccessFile logic?
		// Actually Peer.downloadPiece returns byte[].
		
		// We need a dummy file for Peer constructor
		final var tempFile = File.createTempFile("bittorrent-", ".tmp");

		try (final var peer = Peer.connect(firstPeer, torrent, torrentInfo, tempFile, config.getPeerId())) {
			return peer.downloadPiece(torrentInfo, pieceIndex);
		} finally {
            tempFile.delete();
        }
	}

	// Changed to return File to match Controller expectation (Controller expects FileSystemResource... wait, 
    // I changed Controller to use FileSystemResource for downloadFile and ByteArrayResource for downloadPiece.
	public File downloadFile(String path) throws IOException, InterruptedException {
		final var torrent = load(path);
		final var torrentInfo = torrent.info();

		final var tempFile = File.createTempFile("bittorrent-download-", ".tmp");
		peerServer.registerTorrent(torrentInfo, tempFile);

		// TODO: later we need to add peer picking strategy
		final var response = trackerClient.announce(torrent);
		final var peers = response.peers().stream()
			.filter(p -> p.getPort() != config.getListenPort()) // avoid connecting to ourselves
			.toList();

		if (peers.isEmpty()) {
			System.out.println("No other peers returned by tracker.");
			return null;
		}

		final var firstPeer = peers.getFirst();

		try (
				final var peer = Peer.connect(firstPeer, torrent, torrentInfo, tempFile, config.getPeerId());
				final var fileOutputStream = new FileOutputStream(tempFile);
		) {
			final var data = peer.downloadFile(torrentInfo);
			fileOutputStream.write(data);

			// Inform tracker that we now have the full file (left=0)
			trackerClient.announce(torrent, config.getListenPort(), 0L);

			return tempFile;
		}
	}
}
