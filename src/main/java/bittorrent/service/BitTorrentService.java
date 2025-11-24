package bittorrent.service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.google.gson.Gson;

import bittorrent.BitTorrentApplication;
import bittorrent.bencode.BencodeDeserializer;
import bittorrent.config.BitTorrentConfig;
import bittorrent.magnet.Magnet;
import bittorrent.peer.Peer;
import bittorrent.peer.PeerServer;
import bittorrent.torrent.Torrent;
import bittorrent.torrent.TorrentInfo;
import bittorrent.tracker.TrackerClient;
import bittorrent.tracker.Announceable;
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

	public String decode(String encoded) throws IOException {
		final var decoded = new BencodeDeserializer(encoded).parse();
		return gson.toJson(decoded);
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

	public File downloadPiece(String path, int pieceIndex) throws IOException, InterruptedException {
		final var torrent = load(path);
		final var torrentInfo = torrent.info();

		// Register torrent with PeerServer for seeding
		final var fullFile = new File(torrentInfo.name());
		peerServer.registerTorrent(torrentInfo, fullFile);

		final var firstPeer = trackerClient.announce(torrent).peers().getFirst();
		final var tempFile = File.createTempFile("piece-", ".bin");

		try (
			final var peer = Peer.connect(firstPeer, torrent, torrentInfo, fullFile, config.getPeerId());
			final var fileOutputStream = new FileOutputStream(tempFile);
		) {
			final var data = peer.downloadPiece(torrentInfo, pieceIndex);
			fileOutputStream.write(data);
			return tempFile; // Return file object for controller to handle
		}
	}

	public File downloadFile(String path) throws IOException, InterruptedException {
		final var torrent = load(path);
		final var torrentInfo = torrent.info();

		// Register torrent with PeerServer for seeding
		final var tempFile = new File(torrentInfo.name());
		peerServer.registerTorrent(torrentInfo, tempFile);

		// TODO: later we need to add peer picking strategy
		final var firstPeer = trackerClient.announce(torrent).peers().getFirst();

		try (
				final var peer = Peer.connect(firstPeer, torrent, torrentInfo, tempFile, config.getPeerId());
				final var fileOutputStream = new FileOutputStream(tempFile);
		) {
			final var data = peer.downloadFile(torrentInfo);
			fileOutputStream.write(data);
			return tempFile;
		}
	}
	
	// Implementation for Magnet links and full download would follow similar patterns
	// ... (Other command methods here) ...
}
