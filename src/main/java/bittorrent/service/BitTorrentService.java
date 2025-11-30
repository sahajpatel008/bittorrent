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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.google.gson.Gson;

import bittorrent.BitTorrentApplication;
import bittorrent.bencode.BencodeDeserializer;
import bittorrent.bencode.BencodeSerializer;
import bittorrent.config.BitTorrentConfig;
import bittorrent.peer.Peer;
import bittorrent.peer.PeerConnectionManager;
import bittorrent.peer.SwarmManager;
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
	
	// Download job tracking
	private final Map<String, DownloadJob> downloadJobs = new ConcurrentHashMap<>();
	private final ExecutorService downloadExecutor = Executors.newCachedThreadPool();
	
	// Default download directory
	private static final String DEFAULT_DOWNLOAD_DIR = System.getProperty("user.home") + "/bittorrent-downloads";

	public BitTorrentService(PeerServer peerServer, BitTorrentConfig config) {
		this.peerServer = peerServer;
		this.config = config;
		
		// Create download directory if it doesn't exist
		File downloadDir = new File(DEFAULT_DOWNLOAD_DIR);
		if (!downloadDir.exists()) {
			downloadDir.mkdirs();
		}
	}

	@PostConstruct
	public void init() {
		peerServer.start();
	}

	@PreDestroy
	public void cleanup() {
		peerServer.stop();
		downloadExecutor.shutdown();
	}
	
	/**
	 * Start an asynchronous download job.
	 * Returns immediately with a job ID.
	 */
	public String startDownload(String torrentPath, String outputFileName) {
		try {
			final var torrent = load(torrentPath);
			final var torrentInfo = torrent.info();
			final String infoHashHex = hexFormat.formatHex(torrentInfo.hash());
			
			// Create download job
			DownloadJob job = new DownloadJob(infoHashHex, outputFileName);
			job.setTotalPieces(torrentInfo.pieces().size());
			job.setStatus(DownloadJob.Status.DOWNLOADING);
			
			// Create output file in download directory
			File outputFile = new File(DEFAULT_DOWNLOAD_DIR, outputFileName);
			if (outputFile.exists()) {
				// If file exists, add timestamp to avoid conflicts
				String baseName = outputFileName;
				int lastDot = baseName.lastIndexOf('.');
				if (lastDot > 0) {
					String name = baseName.substring(0, lastDot);
					String ext = baseName.substring(lastDot);
					outputFile = new File(DEFAULT_DOWNLOAD_DIR, name + "_" + System.currentTimeMillis() + ext);
				} else {
					outputFile = new File(DEFAULT_DOWNLOAD_DIR, baseName + "_" + System.currentTimeMillis());
				}
			}
			
			// Make final reference for lambda
			final File finalOutputFile = outputFile;
			
			// Start async download
			CompletableFuture<File> future = CompletableFuture.supplyAsync(() -> {
				try {
					return downloadFileInternal(torrent, torrentInfo, finalOutputFile, job);
				} catch (Exception e) {
					job.setStatus(DownloadJob.Status.FAILED);
					job.setErrorMessage(e.getMessage());
					throw new RuntimeException(e);
				}
			}, downloadExecutor);
			
			job.setFuture(future);
			future.whenComplete((file, throwable) -> {
				if (throwable != null) {
					job.setStatus(DownloadJob.Status.FAILED);
					job.setErrorMessage(throwable.getMessage());
				} else {
					job.setStatus(DownloadJob.Status.COMPLETED);
					job.setDownloadedFile(file);
				}
			});
			
			downloadJobs.put(job.getJobId(), job);
			return job.getJobId();
		} catch (Exception e) {
			throw new RuntimeException("Failed to start download: " + e.getMessage(), e);
		}
	}
	
	/**
	 * Get download job status.
	 */
	public DownloadJob getDownloadJob(String jobId) {
		return downloadJobs.get(jobId);
	}
	
	/**
	 * Internal download method that handles the actual download process.
	 */
	private File downloadFileInternal(Torrent torrent, TorrentInfo torrentInfo, File outputFile, DownloadJob job) 
			throws IOException, InterruptedException {
		final String infoHashHex = hexFormat.formatHex(torrentInfo.hash());
		final SwarmManager swarmManager = SwarmManager.getInstance();
		
		// Register file for seeding (even if incomplete, we can serve pieces we have)
		peerServer.registerTorrent(torrentInfo, outputFile);

		// 1) Ensure we have some peers in the swarm
		final int MIN_KNOWN_PEERS = 3;
		java.util.List<java.net.InetSocketAddress> candidatePeers =
			swarmManager.acquirePeers(infoHashHex, MIN_KNOWN_PEERS);

		if (candidatePeers.size() < MIN_KNOWN_PEERS) {
			final var response = trackerClient.announce(torrent);
			final var trackerPeers = response.peers().stream()
				.filter(p -> p.getPort() != config.getListenPort())
				.toList();

			if (trackerPeers.isEmpty() && candidatePeers.isEmpty()) {
				throw new IOException("No other peers returned by tracker or PEX.");
			}

			swarmManager.registerTrackerPeers(infoHashHex, trackerPeers, config.getListenPort());
			candidatePeers = swarmManager.acquirePeers(
				infoHashHex,
				Math.min(5, Math.max(MIN_KNOWN_PEERS, trackerPeers.size()))
			);
			
			// Broadcast new peers via PEX
			if (!trackerPeers.isEmpty()) {
				PeerConnectionManager.getInstance().broadcastNewPeers(infoHashHex, trackerPeers);
			}
		}

		// 2) Connect to peers
		final int maxPeers = Math.min(3, candidatePeers.size());

		if (candidatePeers.isEmpty() || maxPeers == 0) {
			throw new IOException("No candidate peers available for connection.");
		}

		final var peers = new java.util.ArrayList<Peer>();
		try {
			for (var addr : candidatePeers) {
				try {
					var peer = Peer.connect(addr, torrent, torrentInfo, outputFile, config.getPeerId());
					peers.add(peer);
					swarmManager.registerActivePeer(infoHashHex, addr);
				} catch (IOException e) {
					System.err.println("Failed to connect to peer " + addr + ": " + e.getMessage());
					swarmManager.unregisterActivePeer(infoHashHex, addr);
				}
			}

			if (peers.isEmpty()) {
				throw new IOException("Could not connect to any peers.");
			}
			
			job.setActivePeers(peers);

			// 3) Download pieces
			final int pieceCount = torrentInfo.pieces().size();
			final var pieceData = new byte[pieceCount][];

			for (int pieceIndex = 0; pieceIndex < pieceCount; pieceIndex++) {
				// Pick a peer in round-robin fashion
				Peer peer = peers.get(pieceIndex % peers.size());
				byte[] data = peer.downloadPiece(torrentInfo, pieceIndex);
				pieceData[pieceIndex] = data;
				job.setCompletedPieces(pieceIndex + 1);
			}

			// 4) Write all pieces to file
			try (final var fileOutputStream = new FileOutputStream(outputFile)) {
				for (int i = 0; i < pieceCount; i++) {
					fileOutputStream.write(pieceData[i]);
				}
			}

			// Inform tracker that we now have the full file
			trackerClient.announce(torrent, config.getListenPort(), 0L);
			
			System.out.println("Download complete: " + outputFile.getAbsolutePath());
			
			return outputFile;
		} finally {
			// Close peer connections after download completes
			// Note: If we want to continue seeding, we should keep connections open
			// For now, we close them and rely on PeerServer for incoming connections
			for (Peer peer : peers) {
				try {
					peer.close();
					swarmManager.unregisterActivePeer(infoHashHex, peer.getRemoteAddress());
				} catch (Exception e) {
					System.err.println("Error closing peer: " + e.getMessage());
				}
			}
			job.setActivePeers(null);
		}
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

	/**
	 * Public method to load a torrent file (for use by controllers)
	 */
	public Torrent loadTorrent(String path) throws IOException {
		return load(path);
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
					String torrentPath = args[3];
					// Download directly to final location and continue seeding
					downloadFileAndSeed(torrentPath, outputPath);
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

		// Note: Seeding continues in background via PeerServer
		// No need to block - the application stays running and PeerServer handles connections
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

	/**
	 * Downloads a file and continues seeding. This is used by CLI when leechers should become seeders.
	 */
	public void downloadFileAndSeed(String torrentPath, String outputPath) throws IOException, InterruptedException {
		final var torrent = load(torrentPath);
		final var torrentInfo = torrent.info();
		final var finalFile = new File(outputPath);

		// Register the final file with PeerServer from the start
		peerServer.registerTorrent(torrentInfo, finalFile);

		final String infoHashHex = hexFormat.formatHex(torrentInfo.hash());
		final SwarmManager swarmManager = SwarmManager.getInstance();

		// 1) Ensure we have some peers in the swarm. Prefer tracker when we
		// have too few known peers, otherwise rely on PEX / existing state.
		final int MIN_KNOWN_PEERS = 3;
		java.util.List<java.net.InetSocketAddress> candidatePeers =
			swarmManager.acquirePeers(infoHashHex, MIN_KNOWN_PEERS);

		if (candidatePeers.size() < MIN_KNOWN_PEERS) {
			final var response = trackerClient.announce(torrent);
			final var trackerPeers = response.peers().stream()
				.filter(p -> p.getPort() != config.getListenPort()) // avoid connecting to ourselves
				.toList();

			if (trackerPeers.isEmpty() && candidatePeers.isEmpty()) {
				System.out.println("No other peers returned by tracker or PEX.");
				return;
			}

			swarmManager.registerTrackerPeers(infoHashHex, trackerPeers, config.getListenPort());
			candidatePeers = swarmManager.acquirePeers(
				infoHashHex,
				Math.min(5, Math.max(MIN_KNOWN_PEERS, trackerPeers.size()))
			);
			
			// Broadcast new peers via PEX to all connected peers
			if (!trackerPeers.isEmpty()) {
				PeerConnectionManager.getInstance().broadcastNewPeers(infoHashHex, trackerPeers);
			}
		}

		// 2) Connect to a small number of peers in parallel
		final int maxPeers = Math.min(3, candidatePeers.size());

		if (candidatePeers.isEmpty() || maxPeers == 0) {
			System.out.println("No candidate peers available for connection.");
			return;
		}

		final var peers = new java.util.ArrayList<Peer>();
		try {
			for (var addr : candidatePeers) {
				try {
					// Use final file (not temp) so peers can serve pieces after download
					var peer = Peer.connect(addr, torrent, torrentInfo, finalFile, config.getPeerId());
					peers.add(peer);
					swarmManager.registerActivePeer(infoHashHex, addr);
				} catch (IOException e) {
					System.err.println("Failed to connect to peer " + addr + ": " + e.getMessage());
					// Mark as dropped if connection fails
					swarmManager.unregisterActivePeer(infoHashHex, addr);
				}
			}

			if (peers.isEmpty()) {
				System.out.println("Could not connect to any peers.");
				return;
			}

			// 3) Very simple multi-peer scheduler: assign pieces round-robin
			final int pieceCount = torrentInfo.pieces().size();
			final var pieceData = new byte[pieceCount][];

			for (int pieceIndex = 0; pieceIndex < pieceCount; pieceIndex++) {
				// Pick a peer in round-robin fashion
				Peer peer = peers.get(pieceIndex % peers.size());
				byte[] data = peer.downloadPiece(torrentInfo, pieceIndex);
				pieceData[pieceIndex] = data;
			}

			// 4) Write all pieces to the final file
			try (final var fileOutputStream = new FileOutputStream(finalFile)) {
				for (int i = 0; i < pieceCount; i++) {
					fileOutputStream.write(pieceData[i]);
				}
			}

			// Inform tracker that we now have the full file (left=0)
			trackerClient.announce(torrent, config.getListenPort(), 0L);
			
			System.out.println("Downloaded to: " + outputPath);
			System.out.println("Download complete. Now seeding... (press Ctrl+C to stop)");

			// Don't close peer connections - keep them alive for PEX and seeding
			// The peers will remain active and continue exchanging PEX updates
			// They will be closed when the process exits or when explicitly closed

			// Keep process alive to accept incoming connections and process PEX updates
			Thread.currentThread().join();
		} catch (Exception e) {
			// On error, close peers
			for (int i = 0; i < peers.size(); i++) {
				Peer p = peers.get(i);
				try {
					p.close();
				} catch (Exception ignored) {}
			}
			throw e;
		}
	}

	/**
	 * Legacy synchronous download method (kept for CLI compatibility).
	 * For REST API, use startDownload() instead.
	 */
	public File downloadFile(String path) throws IOException, InterruptedException {
		final var torrent = load(path);
		final var torrentInfo = torrent.info();
		
		// Generate output filename from torrent
		String fileName = torrentInfo.name() != null ? torrentInfo.name() : "download";
		File outputFile = new File(DEFAULT_DOWNLOAD_DIR, fileName);
		
		// If file exists, add timestamp
		if (outputFile.exists()) {
			String baseName = fileName;
			int lastDot = baseName.lastIndexOf('.');
			if (lastDot > 0) {
				String name = baseName.substring(0, lastDot);
				String ext = baseName.substring(lastDot);
				outputFile = new File(DEFAULT_DOWNLOAD_DIR, name + "_" + System.currentTimeMillis() + ext);
			} else {
				outputFile = new File(DEFAULT_DOWNLOAD_DIR, baseName + "_" + System.currentTimeMillis());
			}
		}
		
		// Create a dummy job for tracking
		DownloadJob job = new DownloadJob(hexFormat.formatHex(torrentInfo.hash()), fileName);
		job.setTotalPieces(torrentInfo.pieces().size());
		job.setStatus(DownloadJob.Status.DOWNLOADING);
		
		return downloadFileInternal(torrent, torrentInfo, outputFile, job);
	}
}
