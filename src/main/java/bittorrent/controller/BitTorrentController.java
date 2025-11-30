package bittorrent.controller;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import bittorrent.Main;
import bittorrent.peer.PeerServer;
import bittorrent.peer.SwarmManager;
import bittorrent.service.BitTorrentService;
import bittorrent.service.DownloadJob;
import bittorrent.torrent.Torrent;
import bittorrent.torrent.TorrentInfo;

@RestController
@RequestMapping("/api")
public class BitTorrentController {

	private final BitTorrentService bitTorrentService;
	private final PeerServer peerServer;

	@Autowired
	public BitTorrentController(BitTorrentService bitTorrentService, PeerServer peerServer) {
		this.bitTorrentService = bitTorrentService;
		this.peerServer = peerServer;
	}

	@GetMapping("/")
	public ResponseEntity<Map<String, String>> home() {
		Map<String, String> response = new HashMap<>();
		response.put("status", "running");
		response.put("message", "BitTorrent API is running");
		return ResponseEntity.ok(response);
	}

	/**
	 * Get torrent metadata from uploaded .torrent file
	 * POST /api/torrents/info
	 */
	@PostMapping("/torrents/info")
	public ResponseEntity<Map<String, Object>> getTorrentInfo(@RequestParam("file") MultipartFile file) {
		try {
			final var tempFile = java.io.File.createTempFile("torrent-", ".torrent");
			file.transferTo(tempFile);
			
			final var torrent = bitTorrentService.loadTorrent(tempFile.getAbsolutePath());
			final var info = torrent.info();
			
			Map<String, Object> response = new HashMap<>();
			response.put("trackerUrl", torrent.announce());
			response.put("name", info.name());
			response.put("length", info.length());
			response.put("pieceLength", info.pieceLength());
			response.put("pieceCount", info.pieces().size());
			response.put("infoHash", Main.HEX_FORMAT.formatHex(info.hash()));
			
			tempFile.delete();
			
			return ResponseEntity.ok(response);
		} catch (IOException e) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(Map.of("error", "Failed to parse torrent file: " + e.getMessage()));
		}
	}

	/**
	 * Download a specific piece from a torrent
	 * POST /api/torrents/download/piece/{pieceIndex}
	 */
	@PostMapping("/torrents/download/piece/{pieceIndex}")
	public ResponseEntity<Resource> downloadPiece(
			@PathVariable int pieceIndex, 
			@RequestParam("file") MultipartFile file) {
		try {
			final var tempTorrentFile = java.io.File.createTempFile("torrent-", ".torrent");
			file.transferTo(tempTorrentFile);
			
			final byte[] downloadedPiece = bitTorrentService.downloadPiece(tempTorrentFile.getAbsolutePath(), pieceIndex);
			
			tempTorrentFile.delete();
			
			final var resource = new ByteArrayResource(downloadedPiece);
			
			return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"piece-%d.bin\"".formatted(pieceIndex))
				.contentType(MediaType.APPLICATION_OCTET_STREAM)
				.body(resource);
		} catch (Exception e) {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(new ByteArrayResource(("Error: " + e.getMessage()).getBytes()));
		}
	}

	/**
	 * Start an asynchronous download job
	 * POST /api/torrents/download
	 */
	@PostMapping("/torrents/download")
	public ResponseEntity<Map<String, Object>> startDownload(
			@RequestParam("file") MultipartFile file,
			@RequestParam(value = "outputFileName", required = false) String outputFileName) {
		try {
			final var tempTorrentFile = java.io.File.createTempFile("torrent-", ".torrent");
			file.transferTo(tempTorrentFile);
			
			// Get torrent info to determine default filename
			final var torrent = bitTorrentService.loadTorrent(tempTorrentFile.getAbsolutePath());
			final var torrentInfo = torrent.info();
			
			// Use provided filename or derive from torrent
			String fileName = outputFileName != null ? outputFileName : 
				(torrentInfo.name() != null ? torrentInfo.name() : "download");
			
			// Start async download
			final String jobId = bitTorrentService.startDownload(tempTorrentFile.getAbsolutePath(), fileName);
			
			tempTorrentFile.delete();
			
			Map<String, Object> response = new HashMap<>();
			response.put("jobId", jobId);
			response.put("status", "started");
			response.put("message", "Download started. Use /api/torrents/download/" + jobId + "/status to check progress.");
			
			return ResponseEntity.accepted().body(response);
		} catch (Exception e) {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(Map.of("error", "Failed to start download: " + e.getMessage()));
		}
	}
	
	/**
	 * Get download job status
	 * GET /api/torrents/download/{jobId}/status
	 */
	@GetMapping("/torrents/download/{jobId}/status")
	public ResponseEntity<Map<String, Object>> getDownloadStatus(@PathVariable String jobId) {
		final DownloadJob job = bitTorrentService.getDownloadJob(jobId);
		
		if (job == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(Map.of("error", "Download job not found"));
		}
		
		Map<String, Object> response = new HashMap<>();
		response.put("jobId", job.getJobId());
		response.put("status", job.getStatus().name().toLowerCase());
		response.put("fileName", job.getFileName());
		response.put("infoHash", job.getInfoHashHex());
		response.put("totalPieces", job.getTotalPieces());
		response.put("completedPieces", job.getCompletedPieces());
		response.put("progress", job.getProgress());
		
		if (job.getErrorMessage() != null) {
			response.put("error", job.getErrorMessage());
		}
		
		if (job.getStatus() == DownloadJob.Status.COMPLETED && job.getDownloadedFile() != null) {
			response.put("filePath", job.getDownloadedFile().getAbsolutePath());
			response.put("fileSize", job.getDownloadedFile().length());
		}
		
		return ResponseEntity.ok(response);
	}
	
	/**
	 * Download the completed file
	 * GET /api/torrents/download/{jobId}/file
	 */
	@GetMapping("/torrents/download/{jobId}/file")
	public ResponseEntity<Resource> getDownloadedFile(@PathVariable String jobId) {
		final DownloadJob job = bitTorrentService.getDownloadJob(jobId);
		
		if (job == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(new ByteArrayResource("Download job not found".getBytes()));
		}
		
		if (job.getStatus() != DownloadJob.Status.COMPLETED) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(new ByteArrayResource(("Download not completed. Status: " + job.getStatus()).getBytes()));
		}
		
		if (job.getDownloadedFile() == null || !job.getDownloadedFile().exists()) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(new ByteArrayResource("Downloaded file not found".getBytes()));
		}
		
		final var resource = new FileSystemResource(job.getDownloadedFile());
		
		return ResponseEntity.ok()
			.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + job.getFileName() + "\"")
			.contentType(MediaType.APPLICATION_OCTET_STREAM)
			.body(resource);
	}

	/**
	 * Start seeding a torrent file
	 * POST /api/torrents/seed
	 */
	@PostMapping("/torrents/seed")
	public ResponseEntity<Map<String, Object>> startSeeding(
			@RequestParam("torrent") MultipartFile torrentFile,
			@RequestParam("file") MultipartFile dataFile) {
		try {
			final var tempTorrentFile = java.io.File.createTempFile("torrent-", ".torrent");
			final var tempDataFile = java.io.File.createTempFile("data-", "");
			
			torrentFile.transferTo(tempTorrentFile);
			dataFile.transferTo(tempDataFile);
			
			// Move data file to permanent location
			final var torrent = bitTorrentService.loadTorrent(tempTorrentFile.getAbsolutePath());
			final var torrentInfo = torrent.info();
			final String fileName = torrentInfo.name() != null ? torrentInfo.name() : "seed-" + System.currentTimeMillis();
			final var permanentDataFile = new java.io.File(System.getProperty("user.home") + "/bittorrent-downloads", fileName);
			permanentDataFile.getParentFile().mkdirs();
			
			// Copy temp file to permanent location
			java.nio.file.Files.copy(tempDataFile.toPath(), permanentDataFile.toPath(), 
				java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			
			bitTorrentService.seed(tempTorrentFile.getAbsolutePath(), permanentDataFile.getAbsolutePath());
			
			final var infoHash = Main.HEX_FORMAT.formatHex(torrent.info().hash());
			
			tempTorrentFile.delete();
			tempDataFile.delete();
			
			Map<String, Object> response = new HashMap<>();
			response.put("status", "seeding");
			response.put("infoHash", infoHash);
			response.put("filePath", permanentDataFile.getAbsolutePath());
			response.put("message", "Torrent is now being seeded");
			
			return ResponseEntity.ok(response);
		} catch (Exception e) {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(Map.of("error", "Failed to start seeding: " + e.getMessage()));
		}
	}

	/**
	 * Get list of active torrents (currently seeding)
	 * GET /api/torrents
	 */
	@GetMapping("/torrents")
	public ResponseEntity<List<Map<String, Object>>> getActiveTorrents() {
		// This would require tracking active torrents in PeerServer
		// For now, return empty list or implement tracking
		return ResponseEntity.ok(List.of());
	}

	/**
	 * Get status of a specific torrent
	 * GET /api/torrents/{infoHash}/status
	 */
	@GetMapping("/torrents/{infoHash}/status")
	public ResponseEntity<Map<String, Object>> getTorrentStatus(@PathVariable String infoHash) {
		try {
			byte[] infoHashBytes = Main.HEX_FORMAT.parseHex(infoHash);
			String status = peerServer.getTorrentStatus(infoHashBytes);
			
			Map<String, Object> response = new HashMap<>();
			response.put("infoHash", infoHash);
			response.put("status", status);
			response.put("seeding", status.contains("Seeding: Yes"));
			
			// Get peer counts from SwarmManager
			var swarmManager = SwarmManager.getInstance();
			// Note: SwarmManager doesn't expose peer counts directly, would need to add methods
			
			return ResponseEntity.ok(response);
		} catch (Exception e) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(Map.of("error", "Invalid info hash: " + e.getMessage()));
		}
	}

	/**
	 * Get peers for a specific torrent
	 * GET /api/torrents/{infoHash}/peers
	 */
	@GetMapping("/torrents/{infoHash}/peers")
	public ResponseEntity<Map<String, Object>> getTorrentPeers(@PathVariable String infoHash) {
		try {
			var swarmManager = SwarmManager.getInstance();
			var peers = swarmManager.acquirePeers(infoHash, 50);
			
			List<Map<String, String>> peerList = peers.stream()
				.map(addr -> Map.of(
					"ip", addr.getAddress().getHostAddress(),
					"port", String.valueOf(addr.getPort())
				))
				.collect(Collectors.toList());
			
			Map<String, Object> response = new HashMap<>();
			response.put("infoHash", infoHash);
			response.put("peers", peerList);
			response.put("count", peerList.size());
			
			return ResponseEntity.ok(response);
		} catch (Exception e) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(Map.of("error", "Failed to get peers: " + e.getMessage()));
		}
	}

	/**
	 * Stop seeding a torrent (remove from active torrents)
	 * DELETE /api/torrents/{infoHash}
	 */
	@DeleteMapping("/torrents/{infoHash}")
	public ResponseEntity<Map<String, Object>> stopSeeding(@PathVariable String infoHash) {
		// Note: PeerServer doesn't have a method to unregister torrents yet
		// This would need to be implemented
		Map<String, Object> response = new HashMap<>();
		response.put("infoHash", infoHash);
		response.put("message", "Stop seeding not yet implemented");
		return ResponseEntity.ok(response);
	}
}
