package bittorrent.controller;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import bittorrent.service.BitTorrentService;

@RestController
public class BitTorrentController {

	private final BitTorrentService bitTorrentService;

	@Autowired
	public BitTorrentController(BitTorrentService bitTorrentService) {
		this.bitTorrentService = bitTorrentService;
	}

	@GetMapping("/")
	public String home() {
		return "BitTorrent API is running. Use /api/decode, /api/info, etc.";
	}

	// Example: GET /api/decode?encoded=i10e
	@GetMapping("/api/decode")
	public String decode(@RequestParam String encoded) throws IOException {
		return bitTorrentService.decode(encoded);
	}

	// Example: POST /api/info (with .torrent file upload)
	@PostMapping("/api/info")
	public String getInfo(@RequestParam("file") MultipartFile file) {
		try {
			// Save the uploaded file temporarily
			final var tempFile = java.io.File.createTempFile("torrent-", ".torrent");
			file.transferTo(tempFile);
			
			final var info = bitTorrentService.getInfo(tempFile.getAbsolutePath());
			
			// Clean up
			tempFile.delete();
			
			return info;
		} catch (IOException e) {
			throw new UncheckedIOException("Error processing torrent file", e);
		}
	}
	
	// Example: GET /api/handshake?peer=127.0.0.1:6881&file=/path/to/torrent
	@GetMapping("/api/handshake")
	public String handshake(
			@RequestParam String peer,
			@RequestParam String file) throws IOException, InterruptedException {
		
			// TODO: Take the actual file from the GUI  (we take the actual file content)
		// NOTE: In a real app, file content should be uploaded, not referenced by path
		return bitTorrentService.handshake(file, peer);
	}

	@GetMapping("/api/debug/status")
	public ResponseEntity<String> getStatus(@RequestParam String path) {
		try {
			return ResponseEntity.ok(bitTorrentService.getSeedingStatus(path));
		} catch (IOException e) {
			return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
		}
	}
	
	// Example: POST /api/download/piece/0 (with .torrent file upload)
	@PostMapping("/api/download/piece/{pieceIndex}")
	public ResponseEntity<Resource> downloadPiece(
			@PathVariable int pieceIndex, 
			@RequestParam("file") MultipartFile file) {
		try {
			final var tempTorrentFile = java.io.File.createTempFile("torrent-", ".torrent");
			file.transferTo(tempTorrentFile);
			
			final byte[] downloadedPiece = bitTorrentService.downloadPiece(tempTorrentFile.getAbsolutePath(), pieceIndex);
			
			tempTorrentFile.delete(); // Clean up torrent file
			
			final var resource = new ByteArrayResource(downloadedPiece);
			
			return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"piece-%d.bin\"".formatted(pieceIndex))
				.contentType(MediaType.APPLICATION_OCTET_STREAM)
				.body(resource);
		} catch (Exception e) {
			e.printStackTrace();
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
		}
	}

	// Example: POST /api/download (with .torrent file upload)
	@PostMapping("/api/download")
	public ResponseEntity<Resource> downloadFile(@RequestParam("file") MultipartFile file) {
		try {
			final var tempTorrentFile = java.io.File.createTempFile("torrent-", ".torrent");
			file.transferTo(tempTorrentFile);

			final var downloadedFile = bitTorrentService.downloadFile(tempTorrentFile.getAbsolutePath());

			tempTorrentFile.delete();

			final var resource = new FileSystemResource(downloadedFile);

			return ResponseEntity.ok()
					.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + downloadedFile.getName() + "\"")
					.contentType(MediaType.APPLICATION_OCTET_STREAM)
					.body(resource);
		} catch (Exception e) {
			e.printStackTrace();
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
		}
	}
}