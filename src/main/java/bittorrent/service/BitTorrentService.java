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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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
import bittorrent.service.PeerStats;
import bittorrent.bencode.BencodeDeserializer;
import bittorrent.torrent.Torrent;
import bittorrent.torrent.TorrentInfo;
import bittorrent.tracker.TrackerClient;
import bittorrent.tracker.TrackerClient.Event;
import bittorrent.util.DigestUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

// Using a @Service to integrate logic into Spring's component model
@Service
public class BitTorrentService {
 
	private final TrackerClient trackerClient;
	private final Gson gson = new Gson();
	private final HexFormat hexFormat = BitTorrentApplication.HEX_FORMAT;
	private final PeerServer peerServer;
	private final BitTorrentConfig config;
	private final TorrentProgressService progressService;
	private final bittorrent.service.storage.TorrentPersistenceService persistenceService;
	
	// Download job tracking
	private final Map<String, DownloadJob> downloadJobs = new ConcurrentHashMap<>();
	private final ExecutorService downloadExecutor = Executors.newCachedThreadPool();
	
	// Track active torrents for periodic re-announcements
	// Map<infoHashHex, Torrent> - tracks torrents that need periodic updates
	private final Map<String, Torrent> activeTorrentsForAnnounce = new ConcurrentHashMap<>();
	// Track which torrents have successfully announced at least once
	// This helps us use Event.STARTED for first successful announce after failures
	private final Map<String, Boolean> torrentsAnnouncedSuccessfully = new ConcurrentHashMap<>();
	private final ScheduledExecutorService announceScheduler = Executors.newScheduledThreadPool(1);
	private ScheduledFuture<?> announceTask;
	private ScheduledFuture<?> saveTask;
	
	// Default download directory
	private static final String DEFAULT_DOWNLOAD_DIR = System.getProperty("user.home") + "/bittorrent-downloads";
	// Re-announce interval: 15 seconds (for faster tracker synchronization)
	private static final long REANNOUNCE_INTERVAL_SECONDS = 15;

	public BitTorrentService(PeerServer peerServer, BitTorrentConfig config, TorrentProgressService progressService,
			bittorrent.service.storage.TorrentPersistenceService persistenceService) {
		this.peerServer = peerServer;
		this.config = config;
		this.progressService = progressService;
		this.persistenceService = persistenceService;

		// Create download directory if it doesn't exist
		File downloadDir = new File(DEFAULT_DOWNLOAD_DIR);
		if (!downloadDir.exists()) {
			downloadDir.mkdirs();
		}
		this.trackerClient = new TrackerClient(config.getPeerId(), config.getListenPort());
	}

	@PostConstruct
	public void init() {
		// Initialize SwarmManager with listen port for port-specific storage
		SwarmManager.initialize(config.getListenPort());
		
		peerServer.start();
		// Start periodic re-announcement task
		announceTask = announceScheduler.scheduleAtFixedRate(
			this::reannounceActiveTorrents,
			REANNOUNCE_INTERVAL_SECONDS,
			REANNOUNCE_INTERVAL_SECONDS,
			TimeUnit.SECONDS
		);
		// Start periodic save task (every 30 seconds)
		saveTask = announceScheduler.scheduleAtFixedRate(
			this::saveState,
			30,
			30,
			TimeUnit.SECONDS
		);
		// Load persisted state
		loadPersistedState();
	}
	
	/**
	 * Load persisted download jobs and resume them
	 */
	private void loadPersistedState() {
		// Load download jobs
		List<bittorrent.service.storage.TorrentPersistenceService.DownloadJobState> jobStates = 
			persistenceService.loadDownloadJobs();
		
		for (var state : jobStates) {
			try {
				// Check if torrent file exists
				String torrentPath = persistenceService.getTorrentFilePath(state.infoHashHex);
				if (torrentPath == null) {
					System.out.println("Skipping job " + state.jobId + ": torrent file not found");
					continue;
				}
				
				// Check if download file exists
				File downloadedFile = state.downloadedFilePath != null ? 
					new File(state.downloadedFilePath) : null;
				
				if (downloadedFile != null && downloadedFile.exists()) {
					// Resume download
					Torrent torrent = load(torrentPath);
					TorrentInfo torrentInfo = torrent.info();
					
					// Create job with persisted jobId
					DownloadJob job = new DownloadJob(state.jobId, state.infoHashHex, state.fileName);
					job.setTotalPieces(state.totalPieces);
					job.setCompletedPieces(state.completedPieces);
					job.setStatus(DownloadJob.Status.DOWNLOADING);
					job.setDownloadedFile(downloadedFile);
					
					// Resume download in background
					CompletableFuture<File> future = CompletableFuture.supplyAsync(() -> {
						try {
							return downloadFileInternal(torrent, torrentInfo, downloadedFile, job);
						} catch (Exception e) {
							// Don't set status here - let downloadFileInternal set it appropriately
							// If it's already set to TRYING_TO_CONNECT, keep it; otherwise it will be set below
							if (job.getStatus() != DownloadJob.Status.TRYING_TO_CONNECT) {
								if (isConnectionError(e)) {
									job.setStatus(DownloadJob.Status.TRYING_TO_CONNECT);
									job.setErrorMessage("Trying to connect to peers: " + e.getMessage());
								} else {
									job.setStatus(DownloadJob.Status.FAILED);
									job.setErrorMessage(e.getMessage());
								}
							}
							throw new RuntimeException(e);
						}
					}, downloadExecutor);
					
					job.setFuture(future);
					future.whenComplete((file, throwable) -> {
						if (throwable != null) {
							// Status may have already been set to TRYING_TO_CONNECT in the catch block
							if (job.getStatus() != DownloadJob.Status.TRYING_TO_CONNECT) {
								Throwable cause = throwable.getCause();
								if (cause != null && isConnectionError((Exception) cause)) {
									job.setStatus(DownloadJob.Status.TRYING_TO_CONNECT);
									job.setErrorMessage("Trying to connect to peers: " + cause.getMessage());
								} else {
									job.setStatus(DownloadJob.Status.FAILED);
									job.setErrorMessage(throwable.getMessage());
								}
							}
						} else {
							job.setStatus(DownloadJob.Status.COMPLETED);
							job.setDownloadedFile(file);
						}
					});
					
					downloadJobs.put(job.getJobId(), job);
					activeTorrentsForAnnounce.put(state.infoHashHex, torrent);
					
					System.out.println("Resumed download job: " + state.jobId + " (" + 
						state.completedPieces + "/" + state.totalPieces + " pieces)");
				}
			} catch (Exception e) {
				System.err.println("Failed to resume download job " + state.jobId + ": " + e.getMessage());
				if (BitTorrentApplication.DEBUG) {
					e.printStackTrace();
				}
			}
		}
		
		// Load seeding torrents (handled by PeerServer)
		peerServer.loadPersistedSeedingTorrents();
		
		// Re-announce all resumed seeding torrents
		for (String infoHashHex : peerServer.getActiveTorrents()) {
			try {
				String torrentPath = persistenceService.getTorrentFilePath(infoHashHex);
				if (torrentPath != null) {
					Torrent torrent = load(torrentPath);
					trackerClient.announce(torrent, config.getListenPort(), 0L, Event.STARTED);
					torrentsAnnouncedSuccessfully.put(infoHashHex, true);
					activeTorrentsForAnnounce.put(infoHashHex, torrent);
					System.out.println("Re-announced resumed seeding torrent: " + infoHashHex);
				}
			} catch (Exception e) {
				// Mark as not announced so we use STARTED on first successful re-announce
				torrentsAnnouncedSuccessfully.put(infoHashHex, false);
				// Still add to activeTorrentsForAnnounce so it gets re-announced periodically
				try {
					String torrentPath = persistenceService.getTorrentFilePath(infoHashHex);
					if (torrentPath != null) {
						Torrent torrent = load(torrentPath);
						activeTorrentsForAnnounce.put(infoHashHex, torrent);
					}
				} catch (Exception ex) {
					// Ignore - will retry later
				}
				System.err.println("Failed to re-announce seeding torrent " + infoHashHex + ": " + e.getMessage());
			}
		}
	}
	
	/**
	 * Save current state (download jobs)
	 */
	private void saveState() {
		persistenceService.saveDownloadJobs(downloadJobs);
	}
	
	/**
	 * Automatically retry downloads in TRYING_TO_CONNECT status when peers become available.
	 */
	private void retryTryingToConnectDownloads() {
		for (DownloadJob job : downloadJobs.values()) {
			if (job.getStatus() == DownloadJob.Status.TRYING_TO_CONNECT) {
				String infoHashHex = job.getInfoHashHex();
				SwarmManager swarmManager = SwarmManager.getInstance();
				
				// Check if we now have peers available
				List<java.net.InetSocketAddress> availablePeers = swarmManager.acquirePeers(infoHashHex, 1);
				if (!availablePeers.isEmpty()) {
					try {
						System.out.println("Peers now available for job " + job.getJobId() + 
							", retrying download...");
						retryFailedDownload(job.getJobId());
					} catch (Exception e) {
						System.err.println("Failed to auto-retry trying-to-connect download " + 
							job.getJobId() + ": " + e.getMessage());
					}
				}
			}
		}
	}

	/**
	 * Periodically re-announce all active torrents to the tracker
	 * to keep them from timing out (tracker removes peers after 30 minutes)
	 */
	private void reannounceActiveTorrents() {
		// First, try to retry downloads that are trying to connect
		retryTryingToConnectDownloads();
		
		// Then, validate and cleanup seeding torrents with missing files
		List<String> removedTorrents = peerServer.validateAndCleanupSeedingTorrents();
		for (String infoHashHex : removedTorrents) {
			activeTorrentsForAnnounce.remove(infoHashHex);
			System.err.println("Removed torrent from active announce list due to missing file: " + infoHashHex);
		}
		
		// Include active download jobs in re-announcement tracking
		for (DownloadJob job : downloadJobs.values()) {
			if (job.getStatus() == DownloadJob.Status.DOWNLOADING) {
				String infoHashHex = job.getInfoHashHex();
				// Ensure downloading torrents are in activeTorrentsForAnnounce
				if (!activeTorrentsForAnnounce.containsKey(infoHashHex)) {
					try {
						String torrentPath = persistenceService.getTorrentFilePath(infoHashHex);
						if (torrentPath != null) {
							Torrent torrent = load(torrentPath);
							activeTorrentsForAnnounce.put(infoHashHex, torrent);
							System.out.println("Added downloading torrent to re-announce list: " + infoHashHex);
						}
					} catch (Exception e) {
						System.err.println("Failed to load torrent for re-announce: " + infoHashHex + " - " + e.getMessage());
					}
				}
			}
		}
		
		// Also ensure all active seeding torrents are in activeTorrentsForAnnounce
		for (String infoHashHex : peerServer.getActiveTorrents()) {
			if (!activeTorrentsForAnnounce.containsKey(infoHashHex)) {
				try {
					String torrentPath = persistenceService.getTorrentFilePath(infoHashHex);
					if (torrentPath != null) {
						Torrent torrent = load(torrentPath);
						activeTorrentsForAnnounce.put(infoHashHex, torrent);
						System.out.println("Added seeding torrent to re-announce list: " + infoHashHex);
					}
				} catch (Exception e) {
					System.err.println("Failed to load seeding torrent for re-announce: " + infoHashHex + " - " + e.getMessage());
				}
			}
		}
		
		if (activeTorrentsForAnnounce.isEmpty()) {
			if (BitTorrentApplication.DEBUG) {
				System.out.println("Re-announcement: No active torrents to announce.");
			}
			return;
		}
		
		if (BitTorrentApplication.DEBUG) {
			System.out.println("Re-announcement: Processing " + activeTorrentsForAnnounce.size() + " torrent(s).");
		}
		
		for (Map.Entry<String, Torrent> entry : activeTorrentsForAnnounce.entrySet()) {
			String infoHashHex = entry.getKey();
			Torrent torrent = entry.getValue();
			
			// Determine if this is a seeding torrent or downloading torrent
			boolean isSeeding = peerServer.isTorrentActive(infoHashHex);
			long left = 0L;
			
			if (isSeeding) {
				// Check if seeding file still exists
				if (!peerServer.isSeedingFileExists(infoHashHex)) {
					System.err.println("Seeding file missing for torrent " + infoHashHex + ", skipping announce");
					continue;
				}
				// Seeding torrent: left = 0
				left = 0L;
			} else {
				// Check if it's an active download job
				DownloadJob job = null;
				for (DownloadJob j : downloadJobs.values()) {
					if (j.getInfoHashHex().equals(infoHashHex) && j.getStatus() == DownloadJob.Status.DOWNLOADING) {
						job = j;
						break;
					}
				}
				
				if (job == null) {
					// Not seeding and not downloading - remove from tracking
					activeTorrentsForAnnounce.remove(infoHashHex);
					continue;
				}
				
				// Calculate remaining bytes for downloading torrent
				TorrentInfo info = torrent.info();
				long totalBytes = info.length();
				long downloadedBytes = (long) (totalBytes * (job.getProgress() / 100.0));
				left = Math.max(0, totalBytes - downloadedBytes);
			}
			
			try {
				// Determine event type: use STARTED if this is first successful announce after failures
				// Otherwise use NONE for regular periodic updates
				Event event = Event.NONE;
				Boolean hasAnnounced = torrentsAnnouncedSuccessfully.get(infoHashHex);
				if (hasAnnounced == null || !hasAnnounced) {
					// First successful announce - use STARTED to ensure tracker registers the peer
					event = Event.STARTED;
				}
				
				// Re-announce with calculated left value
				trackerClient.announce(torrent, config.getListenPort(), left, event);
				torrentsAnnouncedSuccessfully.put(infoHashHex, true);
				System.out.println("Re-announced torrent to tracker: " + infoHashHex + 
					" (left=" + left + ", " + (isSeeding ? "seeding" : "downloading") + 
					", event=" + event + ")");
			} catch (IOException e) {
				// Mark as not successfully announced so we use STARTED next time
				torrentsAnnouncedSuccessfully.put(infoHashHex, false);
				System.err.println("Failed to re-announce torrent " + infoHashHex + ": " + e.getMessage());
			}
		}
	}

	@PreDestroy
	public void cleanup() {
		// Make cleanup completely non-blocking - return immediately
		// All shutdown operations run in background threads to prevent Spring Boot timeout
		Thread cleanupThread = new Thread(() -> {
			try {
				// Stop periodic re-announcements immediately (don't wait for running tasks)
				if (announceTask != null) {
					announceTask.cancel(false);
				}
				// Use shutdownNow() to interrupt any running re-announcement tasks
				// This prevents blocking if a tracker call is in progress
				announceScheduler.shutdownNow();
				
				// Send stopped event to tracker for all active torrents (non-blocking, best-effort)
				// Peer server runs independently, so tracker failures should not block shutdown
				// Start all shutdown threads without waiting - they're daemon threads and will be terminated
				// when JVM shuts down, so we don't need to wait for them
				for (Map.Entry<String, Torrent> entry : activeTorrentsForAnnounce.entrySet()) {
					Torrent torrent = entry.getValue();
					// Use a separate daemon thread to avoid blocking shutdown if tracker is unreachable
					Thread shutdownThread = new Thread(() -> {
						try {
							trackerClient.announce(torrent, config.getListenPort(), 0L, Event.STOPPED);
							if (BitTorrentApplication.DEBUG) {
								System.out.println("Sent stopped event to tracker for: " + entry.getKey());
							}
						} catch (IOException e) {
							// Ignore errors during shutdown - tracker is optional
							if (BitTorrentApplication.DEBUG) {
								System.err.println("Failed to send stopped event: " + e.getMessage());
							}
						}
					}, "TrackerShutdown-" + entry.getKey());
					shutdownThread.setDaemon(true);
					shutdownThread.start();
					// Don't wait for threads - they're daemon threads and will be terminated on JVM shutdown
					// This ensures shutdown completes quickly even if tracker is unavailable
				}
				activeTorrentsForAnnounce.clear();
				
				// Stop peer server (this is independent of tracker)
				peerServer.stop();
				
				// Shutdown download executor - use shutdownNow() to interrupt any blocking operations
				// Don't wait for completion - executor will be terminated when JVM shuts down
				downloadExecutor.shutdownNow();
			} catch (Exception e) {
				// Ignore any errors during cleanup - we're shutting down anyway
				if (BitTorrentApplication.DEBUG) {
					System.err.println("Error during cleanup: " + e.getMessage());
				}
			}
		}, "BitTorrentService-Cleanup");
		cleanupThread.setDaemon(true);
		cleanupThread.start();
		// Return immediately - don't wait for cleanup to complete
		// This prevents Spring Boot from timing out during shutdown
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
			
			// Save torrent file to persistence BEFORE starting download
			// This ensures retry can work even if download fails early
			if (!persistenceService.hasTorrentFile(infoHashHex)) {
				File torrentFile = new File(torrentPath);
				persistenceService.saveTorrentFile(infoHashHex, torrentFile);
			}
			
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
					// Don't set status here - let downloadFileInternal set it appropriately
					// If it's already set to TRYING_TO_CONNECT, keep it; otherwise it will be set below
					if (job.getStatus() != DownloadJob.Status.TRYING_TO_CONNECT) {
						if (isConnectionError(e)) {
							job.setStatus(DownloadJob.Status.TRYING_TO_CONNECT);
							job.setErrorMessage("Trying to connect to peers: " + e.getMessage());
						} else {
							job.setStatus(DownloadJob.Status.FAILED);
							job.setErrorMessage(e.getMessage());
						}
					}
					throw new RuntimeException(e);
				}
			}, downloadExecutor);
			
			job.setFuture(future);
			future.whenComplete((file, throwable) -> {
				if (throwable != null) {
					// Status may have already been set to TRYING_TO_CONNECT in the catch block
					if (job.getStatus() != DownloadJob.Status.TRYING_TO_CONNECT) {
						Throwable cause = throwable.getCause();
						if (cause != null && isConnectionError((Exception) cause)) {
							job.setStatus(DownloadJob.Status.TRYING_TO_CONNECT);
							job.setErrorMessage("Trying to connect to peers: " + cause.getMessage());
						} else {
							job.setStatus(DownloadJob.Status.FAILED);
							job.setErrorMessage(throwable.getMessage());
						}
					}
				} else {
					job.setStatus(DownloadJob.Status.COMPLETED);
					job.setDownloadedFile(file);
				}
			});
			
			downloadJobs.put(job.getJobId(), job);
			
			// Add torrent to activeTorrentsForAnnounce immediately so it gets re-announced periodically
			// This ensures tracker is notified even if it wasn't available when download started
			activeTorrentsForAnnounce.put(infoHashHex, torrent);
			
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
	 * Get all active download jobs (for progress service)
	 */
	public List<DownloadJob> getAllActiveJobs() {
		return new ArrayList<>(downloadJobs.values());
	}
	
	/**
	 * Send progress update via SSE
	 */
	private void sendProgressUpdate(DownloadJob job) {
		Map<String, Object> update = buildProgressUpdate(job);
		progressService.sendUpdate(job.getJobId(), job.getInfoHashHex(), update);
	}
	
	/**
	 * Build progress update map for SSE (public for TorrentProgressService)
	 */
	public Map<String, Object> buildProgressUpdate(DownloadJob job) {
		Map<String, Object> update = new LinkedHashMap<>();
		update.put("jobId", job.getJobId());
		update.put("infoHash", job.getInfoHashHex());
		update.put("fileName", job.getFileName());
		update.put("status", job.getStatus().name());
		update.put("totalPieces", job.getTotalPieces());
		update.put("completedPieces", job.getCompletedPieces());
		update.put("progress", job.getProgress());
		update.put("overallDownloadSpeed", job.getOverallDownloadSpeed());
		update.put("startTime", job.getStartTime());
		update.put("lastUpdateTime", job.getLastUpdateTime());
		
		// Peer statistics
		List<Map<String, Object>> peerStatsList = new ArrayList<>();
		for (Map.Entry<String, PeerStats> entry : job.getPeerStats().entrySet()) {
			PeerStats stats = entry.getValue();
			Map<String, Object> peerInfo = new LinkedHashMap<>();
			peerInfo.put("address", stats.getAddress().toString());
			peerInfo.put("ip", stats.getAddress().getAddress().getHostAddress());
			peerInfo.put("port", stats.getAddress().getPort());
			peerInfo.put("bytesDownloaded", stats.getBytesDownloaded());
			peerInfo.put("bytesUploaded", stats.getBytesUploaded());
			peerInfo.put("piecesDownloaded", stats.getPiecesDownloaded());
			peerInfo.put("piecesUploaded", stats.getPiecesUploaded());
			peerInfo.put("downloadSpeed", stats.getDownloadSpeed());
			peerInfo.put("uploadSpeed", stats.getUploadSpeed());
			peerInfo.put("downloadedPieces", stats.getDownloadedPieces());
			peerInfo.put("connectionDuration", stats.getConnectionDuration());
			peerInfo.put("isChoked", stats.isChoked());
			peerInfo.put("isInterested", stats.isInterested());
			peerInfo.put("peerChoking", stats.isPeerChoking());
			peerInfo.put("peerInterested", stats.isPeerInterested());
			peerStatsList.add(peerInfo);
		}
		update.put("peers", peerStatsList);
		
		// Piece source mapping
		Map<String, String> pieceSource = new LinkedHashMap<>();
		for (Map.Entry<Integer, String> entry : job.getPieceSource().entrySet()) {
			pieceSource.put(String.valueOf(entry.getKey()), entry.getValue());
		}
		update.put("pieceSource", pieceSource);
		
		return update;
	}
	
	/**
	 * Force announce to tracker for a torrent
	 */
	public void forceAnnounce(String infoHashHex) throws IOException {
		// Normalize infoHash (case-insensitive)
		String normalized = infoHashHex.toLowerCase();
		
		// First, try to get from activeTorrentsForAnnounce (try both original and normalized)
		Torrent torrent = activeTorrentsForAnnounce.get(infoHashHex);
		if (torrent == null) {
			torrent = activeTorrentsForAnnounce.get(normalized);
		}
		// Also try case-insensitive lookup
		if (torrent == null) {
			for (Map.Entry<String, Torrent> entry : activeTorrentsForAnnounce.entrySet()) {
				if (entry.getKey().equalsIgnoreCase(infoHashHex)) {
					torrent = entry.getValue();
					break;
				}
			}
		}
		
		// If not found, check if it's a seeding torrent
		if (torrent == null && peerServer.isTorrentActive(normalized)) {
			String torrentPath = persistenceService.getTorrentFilePath(normalized);
			if (torrentPath != null) {
				torrent = load(torrentPath);
				// Add to activeTorrentsForAnnounce for future use
				activeTorrentsForAnnounce.put(normalized, torrent);
			}
		}
		
		// If still not found, check if it's an active download job
		if (torrent == null) {
			for (DownloadJob job : downloadJobs.values()) {
				if (job.getInfoHashHex().toLowerCase().equals(normalized)) {
					String torrentPath = persistenceService.getTorrentFilePath(normalized);
					if (torrentPath != null) {
						torrent = load(torrentPath);
						// Add to activeTorrentsForAnnounce for future use
						activeTorrentsForAnnounce.put(normalized, torrent);
					}
					break;
				}
			}
		}
		
		if (torrent == null) {
			throw new IllegalArgumentException("Torrent not found: " + infoHashHex + 
				" (not in active downloads or seeding torrents)");
		}
		
		// Determine if we're seeding (left=0) or downloading (left>0)
		long left = 0L;
		if (peerServer.isTorrentActive(normalized)) {
			// Seeding torrent
			left = 0L;
		} else {
			// Check if it's a download job
			for (DownloadJob job : downloadJobs.values()) {
				if (job.getInfoHashHex().toLowerCase().equals(normalized)) {
					// Calculate remaining bytes
					TorrentInfo info = torrent.info();
					long totalBytes = info.length();
					long downloadedBytes = (long) (totalBytes * (job.getProgress() / 100.0));
					left = Math.max(0, totalBytes - downloadedBytes);
					break;
				}
			}
		}
		
		// Tracker is optional - don't fail if tracker is unavailable
		try {
			trackerClient.announce(torrent, config.getListenPort(), left, Event.NONE);
		} catch (IOException e) {
			// Tracker unavailable - log but don't throw exception
			// The peer should continue operating independently
			throw new IOException("Tracker unavailable: " + e.getMessage() + 
				". Peer continues operating independently.", e);
		}
	}
	
	/**
	 * Retry a failed download job.
	 * Resets the job status to DOWNLOADING and restarts the download process.
	 */
	public void retryFailedDownload(String jobId) throws IOException {
		DownloadJob job = downloadJobs.get(jobId);
		if (job == null) {
			throw new IllegalArgumentException("Download job not found: " + jobId);
		}
		
		if (job.getStatus() != DownloadJob.Status.FAILED && 
		    job.getStatus() != DownloadJob.Status.TRYING_TO_CONNECT) {
			throw new IllegalStateException("Job is not in FAILED or TRYING_TO_CONNECT status: " + job.getStatus());
		}
		
		// Reset status and clear error message
		job.setStatus(DownloadJob.Status.DOWNLOADING);
		job.setErrorMessage(null);
		
		// Load torrent file from persistence
		String torrentPath = persistenceService.getTorrentFilePath(job.getInfoHashHex());
		if (torrentPath == null) {
			throw new IOException("Torrent file not found for job: " + jobId);
		}
		
		Torrent torrent = load(torrentPath);
		TorrentInfo torrentInfo = torrent.info();
		
		// Get or recreate output file
		File outputFile = job.getDownloadedFile();
		if (outputFile == null || !outputFile.exists()) {
			// Recreate output file if it doesn't exist
			outputFile = new File(DEFAULT_DOWNLOAD_DIR, job.getFileName());
		}
		// Make final reference for lambda
		final File finalOutputFile = outputFile;
		
		// Retry download in background
		CompletableFuture<File> future = CompletableFuture.supplyAsync(() -> {
			try {
				return downloadFileInternal(torrent, torrentInfo, finalOutputFile, job);
			} catch (Exception e) {
				// Don't set status here - let downloadFileInternal set it appropriately
				// If it's already set to TRYING_TO_CONNECT, keep it; otherwise it will be set below
				if (job.getStatus() != DownloadJob.Status.TRYING_TO_CONNECT) {
					if (isConnectionError(e)) {
						job.setStatus(DownloadJob.Status.TRYING_TO_CONNECT);
						job.setErrorMessage("Trying to connect to peers: " + e.getMessage());
					} else {
						job.setStatus(DownloadJob.Status.FAILED);
						job.setErrorMessage(e.getMessage());
					}
				}
				throw new RuntimeException(e);
			}
		}, downloadExecutor);
		
		job.setFuture(future);
		future.whenComplete((file, throwable) -> {
			if (throwable != null) {
				// Status may have already been set to TRYING_TO_CONNECT in the catch block
				if (job.getStatus() != DownloadJob.Status.TRYING_TO_CONNECT) {
					Throwable cause = throwable.getCause();
					if (cause != null && isConnectionError((Exception) cause)) {
						job.setStatus(DownloadJob.Status.TRYING_TO_CONNECT);
						job.setErrorMessage("Trying to connect to peers: " + cause.getMessage());
					} else {
						job.setStatus(DownloadJob.Status.FAILED);
						job.setErrorMessage(throwable.getMessage());
					}
				}
			} else {
				job.setStatus(DownloadJob.Status.COMPLETED);
				job.setDownloadedFile(file);
			}
		});
		
		// Add to activeTorrentsForAnnounce for tracking
		activeTorrentsForAnnounce.put(job.getInfoHashHex(), torrent);
		
		System.out.println("Retrying failed download job: " + jobId);
	}
	
	/**
	 * Discovers and connects to new peers that are available but not yet connected.
	 * Returns list of newly connected peers.
	 */
	private List<Peer> discoverAndConnectNewPeers(
			String infoHashHex,
			Torrent torrent,
			TorrentInfo torrentInfo,
			File outputFile,
			List<Peer> existingPeers,
			SwarmManager swarmManager) {
		
		List<Peer> newPeers = new ArrayList<>();
		
		// Get peers that are known but not currently active
		List<java.net.InetSocketAddress> candidatePeers = swarmManager.acquirePeers(infoHashHex, 5);
		
		// Filter out peers we're already connected to
		java.util.Set<java.net.InetSocketAddress> existingAddresses = existingPeers.stream()
			.map(Peer::getRemoteAddress)
			.collect(java.util.stream.Collectors.toSet());
		
		for (java.net.InetSocketAddress addr : candidatePeers) {
			if (existingAddresses.contains(addr)) {
				continue; // Already connected
			}
			
			try {
				Peer peer = Peer.connect(addr, torrent, torrentInfo, outputFile, config.getPeerId());
				newPeers.add(peer);
				swarmManager.registerActivePeer(infoHashHex, addr);
				
				if (BitTorrentApplication.DEBUG) {
					System.out.println("Discovered and connected to new peer: " + addr);
				}
			} catch (IOException e) {
				System.err.println("Failed to connect to new peer " + addr + ": " + e.getMessage());
				swarmManager.unregisterActivePeer(infoHashHex, addr);
			}
		}
		
		return newPeers;
	}
	
	/**
	 * Attempts to immediately connect to a newly added peer for active downloading jobs.
	 * This is called when a peer is manually added to help active downloads discover it faster.
	 */
	public void tryConnectNewPeerForActiveDownloads(String infoHashHex, java.net.InetSocketAddress address) {
		// Find active DOWNLOADING jobs for this torrent
		for (DownloadJob job : downloadJobs.values()) {
			if (job.getInfoHashHex().equals(infoHashHex) && 
				job.getStatus() == DownloadJob.Status.DOWNLOADING) {
				
				// Try to load torrent and connect
				try {
					String torrentPath = persistenceService.getTorrentFilePath(infoHashHex);
					if (torrentPath == null) {
						continue; // Torrent file not found, skip
					}
					
					Torrent torrent = load(torrentPath);
					TorrentInfo torrentInfo = torrent.info();
					File outputFile = job.getDownloadedFile();
					
					if (outputFile == null || !outputFile.exists()) {
						// Create output file if it doesn't exist
						outputFile = new File(DEFAULT_DOWNLOAD_DIR, job.getFileName());
					}
					
					// Check if we're already connected to this peer via PeerConnectionManager
					var connectionManager = PeerConnectionManager.getInstance();
					List<Peer> existingConnections = connectionManager.getConnections(infoHashHex);
					boolean alreadyConnected = existingConnections.stream()
						.anyMatch(p -> p.getRemoteAddress().equals(address));
					if (alreadyConnected) {
						if (BitTorrentApplication.DEBUG) {
							System.out.println("Already connected to peer " + address + " for job " + job.getJobId());
						}
						continue;
					}
					
					// Try to connect to the new peer
					try {
						Peer peer = Peer.connect(address, torrent, torrentInfo, outputFile, config.getPeerId());
						SwarmManager.getInstance().registerActivePeer(infoHashHex, address);
						
						// Add to job's active peers if available
						if (job.getActivePeers() != null) {
							job.getActivePeers().add(peer);
						}
						
						System.out.println("Immediately connected to manually added peer " + address + 
							" for active download job " + job.getJobId());
					} catch (IOException e) {
						System.err.println("Failed to immediately connect to peer " + address + 
							" for job " + job.getJobId() + ": " + e.getMessage());
						// Don't fail - the download loop will retry later
					}
				} catch (Exception e) {
					System.err.println("Error trying to connect new peer for active download: " + e.getMessage());
				}
			}
		}
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
			// Try to get peers from tracker (initial announcement when download starts)
			java.util.List<java.net.InetSocketAddress> trackerPeers = java.util.List.of();
			try {
				final var response = trackerClient.announce(torrent, config.getListenPort(), torrentInfo.length(), Event.STARTED);
				trackerPeers = response.peers().stream()
					.filter(p -> p.getPort() != config.getListenPort())
					.toList();
				swarmManager.registerTrackerPeers(infoHashHex, trackerPeers, config.getListenPort());
			} catch (IOException e) {
				// Tracker unavailable - log but continue if we have PEX peers
				System.err.println("Tracker unavailable: " + e.getMessage() + ". Relying on PEX peers.");
			}

			if (trackerPeers.isEmpty() && candidatePeers.isEmpty()) {
				// Set status to TRYING_TO_CONNECT instead of failing
				job.setStatus(DownloadJob.Status.TRYING_TO_CONNECT);
				job.setErrorMessage("No peers available: tracker returned no peers and no PEX peers known. " +
					"Waiting for peers to become available...");
				throw new IOException("No peers available: tracker returned no peers and no PEX peers known. " +
					"Cannot bootstrap without at least one peer.");
			}
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
			// Set status to TRYING_TO_CONNECT instead of failing
			job.setStatus(DownloadJob.Status.TRYING_TO_CONNECT);
			job.setErrorMessage("No peers available. Waiting for peers to become available...");
			throw new IOException("No candidate peers available for connection.");
		}

		// Use thread-safe list for peers so we can add new ones during download
		final var peers = new CopyOnWriteArrayList<Peer>();
		try {
			// Initial peer connections
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
				// Set status to TRYING_TO_CONNECT instead of failing
				job.setStatus(DownloadJob.Status.TRYING_TO_CONNECT);
				job.setErrorMessage("Could not connect to any peers. Waiting for peers to become available...");
				throw new IOException("Could not connect to any peers.");
			}
			
			job.setActivePeers(new ArrayList<>(peers)); // Store snapshot for job tracking

			// 3) Pre-allocate file and download pieces incrementally
			final int pieceCount = torrentInfo.pieces().size();
			final long fileLength = torrentInfo.length();
			final int pieceLength = torrentInfo.pieceLength();
			
			// Pre-allocate the file to its full size so pieces can be written at correct positions
			try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(outputFile, "rw")) {
				raf.setLength(fileLength);
			}

			// Download and write pieces incrementally as they're received
			try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(outputFile, "rw")) {
				int peerDiscoveryCounter = 0;
				final int PEER_DISCOVERY_INTERVAL = 10; // Check for new peers every 10 pieces
				
				for (int pieceIndex = 0; pieceIndex < pieceCount; pieceIndex++) {
					// Periodically check for new peers
					if (peerDiscoveryCounter++ % PEER_DISCOVERY_INTERVAL == 0) {
						List<Peer> newPeers = discoverAndConnectNewPeers(
							infoHashHex, torrent, torrentInfo, outputFile, 
							new ArrayList<>(peers), swarmManager);
						
						if (!newPeers.isEmpty()) {
							peers.addAll(newPeers);
							job.setActivePeers(new ArrayList<>(peers)); // Update job with new peers
							if (BitTorrentApplication.DEBUG) {
								System.out.println("Added " + newPeers.size() + " new peer(s). Total: " + peers.size());
							}
						}
					}
					
					// Filter out closed peers before selecting
					List<Peer> availablePeers = peers.stream()
						.filter(p -> !p.isClosed())
						.collect(java.util.stream.Collectors.toList());
					
					if (availablePeers.isEmpty()) {
						// Try to discover new peers one more time
						List<Peer> newPeers = discoverAndConnectNewPeers(
							infoHashHex, torrent, torrentInfo, outputFile, 
							new ArrayList<>(peers), swarmManager);
						
						if (newPeers.isEmpty()) {
							// Set status to TRYING_TO_CONNECT instead of failing
							job.setStatus(DownloadJob.Status.TRYING_TO_CONNECT);
							job.setErrorMessage("No available peers for piece " + pieceIndex + ". Waiting for peers...");
							throw new IOException("No available peers for piece " + pieceIndex);
						}
						peers.addAll(newPeers);
						availablePeers = newPeers;
					}
					
					// Pick a peer in round-robin fashion from available peers
					Peer peer = availablePeers.get(pieceIndex % availablePeers.size());
					java.net.InetSocketAddress peerAddress = peer.getRemoteAddress();
					long pieceSize = (pieceIndex == pieceCount - 1) 
						? (fileLength % pieceLength)
						: pieceLength;
					
					try {
						long startTime = System.currentTimeMillis();
						byte[] data = peer.downloadPiece(torrentInfo, pieceIndex);
						long downloadTime = System.currentTimeMillis() - startTime;
						
						// Write piece immediately to file at correct position
						long pieceStart = (long) pieceIndex * pieceLength;
						raf.seek(pieceStart);
						raf.write(data);
						
						// Force write to disk so piece is immediately available for serving
						raf.getFD().sync();
						
						job.setCompletedPieces(pieceIndex + 1);
						
						// Track peer statistics
						job.recordPieceDownloaded(pieceIndex, peerAddress, pieceSize);
						
						// Update peer stats with connection state
						PeerStats stats = job.getOrCreatePeerStats(peerAddress);
						// Note: Peer connection state would need to be exposed from Peer class
						// For now, we track download stats
						
						// Send progress update after each piece
						sendProgressUpdate(job);
						
						if (BitTorrentApplication.DEBUG) {
							System.out.println("Downloaded and wrote piece " + pieceIndex + "/" + pieceCount + 
								" (" + (pieceIndex + 1) * 100 / pieceCount + "%) from " + peerAddress);
						}
					} catch (IOException | InterruptedException e) {
						// Peer failed, remove it and try another
						System.err.println("Failed to download piece " + pieceIndex + " from " + peerAddress + 
							": " + e.getMessage());
						peers.remove(peer);
						swarmManager.unregisterActivePeer(infoHashHex, peerAddress);
						
						// Try with another peer if available
						availablePeers = peers.stream()
							.filter(p -> !p.isClosed())
							.collect(java.util.stream.Collectors.toList());
						
						if (availablePeers.isEmpty()) {
							throw new IOException("No peers available after failure: " + e.getMessage());
						}
						
						// Retry with different peer
						pieceIndex--; // Retry this piece
						continue;
					}
				}
			}

			// Inform tracker that we now have the full file (completed download, now seeding)
			// Tracker is optional - don't fail the download if tracker is unavailable
			try {
				trackerClient.announce(torrent, config.getListenPort(), 0L, Event.COMPLETED);
			} catch (IOException e) {
				// Tracker unavailable - log but don't fail the download
				System.err.println("Tracker unavailable when announcing completion: " + e.getMessage() + 
					". Download succeeded regardless.");
			}
			
			// Register for periodic re-announcements (now that we're seeding)
			activeTorrentsForAnnounce.put(infoHashHex, torrent);
			
			// Send final progress update
			sendProgressUpdate(job);
			
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

	/**
	 * Determines if an exception is connection-related (should use TRYING_TO_CONNECT status).
	 */
	private boolean isConnectionError(Exception e) {
		String message = e.getMessage();
		if (message == null) {
			return false;
		}
		message = message.toLowerCase();
		return message.contains("no peers available") ||
		       message.contains("no candidate peers") ||
		       message.contains("could not connect") ||
		       message.contains("connection refused") ||
		       message.contains("connection timed out") ||
		       message.contains("no available peers") ||
		       message.contains("cannot bootstrap") ||
		       (e instanceof java.net.ConnectException) ||
		       (e instanceof java.net.SocketTimeoutException);
	}

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
	
	/**
	 * Static method to load torrent (for use by PeerServer)
	 */
	public static Torrent loadTorrentStatic(String path) throws IOException {
		final var content = Files.readAllBytes(Paths.get(path));
		final var decoded = new BencodeDeserializer(content).parse();
		@SuppressWarnings("unchecked")
		Map<String, Object> decodedMap = (Map<String, Object>) decoded;
		return Torrent.of(decodedMap);
	}
	
	/**
	 * Save torrent file permanently
	 */
	public void saveTorrentFile(String infoHashHex, File torrentFile) throws IOException {
		persistenceService.saveTorrentFile(infoHashHex, torrentFile);
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
		final String infoHashHex = hexFormat.formatHex(torrentInfo.hash());
		final var file = new File(filePath);

		if (!file.exists()) {
			throw new IOException("File to seed does not exist: " + filePath);
		}

		// Save torrent file if not already saved
		if (!persistenceService.hasTorrentFile(infoHashHex)) {
			File torrentFile = new File(torrentPath);
			persistenceService.saveTorrentFile(infoHashHex, torrentFile);
		}

		// Register file with PeerServer so we can serve pieces
		// This is independent of tracker - seeding works immediately
		peerServer.registerTorrent(torrentInfo, file);
		System.out.println("Registered " + filePath + " for seeding. Seeding is active and independent of tracker.");

		// Register for periodic re-announcements FIRST (before attempting tracker announce)
		// This ensures the torrent will be announced to tracker when it becomes available
		activeTorrentsForAnnounce.put(infoHashHex, torrent);
		
		// Attempt to announce to tracker (optional - seeding works regardless)
		// Tracker will be notified via periodic re-announcements (every 15 seconds) if unavailable now
		try {
			trackerClient.announce(torrent, config.getListenPort(), 0L, Event.STARTED);
			torrentsAnnouncedSuccessfully.put(infoHashHex, true);
			System.out.println("Announced to tracker on port " + config.getListenPort() + ". Seeding...");
		} catch (IOException e) {
			// Tracker unavailable - mark as not announced so we use STARTED on first successful re-announce
			torrentsAnnouncedSuccessfully.put(infoHashHex, false);
			// Tracker unavailable - log but continue seeding
			// Seeding is fully functional - tracker will be notified on next re-announce (15 seconds)
			System.out.println("Tracker unavailable when starting to seed: " + e.getMessage() + 
				". Seeding is active and will announce to tracker when it becomes available (next re-announce in 15 seconds).");
		}
		
		// Save seeding state
		peerServer.saveSeedingTorrents();

		// Note: Seeding continues in background via PeerServer
		// No need to block - the application stays running and PeerServer handles connections
		// Tracker communication happens asynchronously via periodic re-announcements
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

	/**
	 * Remove torrent metadata, cancel active jobs, and stop seeding.
	 */
	public boolean deleteTorrent(String infoHashHex) {
		if (infoHashHex == null || infoHashHex.isBlank()) {
			throw new IllegalArgumentException("infoHash is required");
		}
		String normalized = infoHashHex.toLowerCase();
		boolean removed = false;

		// Cancel and remove download jobs tied to this torrent
		List<String> jobsToRemove = new ArrayList<>();
		for (DownloadJob job : downloadJobs.values()) {
			if (job.getInfoHashHex().equalsIgnoreCase(normalized)) {
				if (job.getFuture() != null) {
					job.getFuture().cancel(true);
				}
				job.setStatus(DownloadJob.Status.CANCELLED);
				jobsToRemove.add(job.getJobId());
				removed = true;
			}
		}
		for (String jobId : jobsToRemove) {
			downloadJobs.remove(jobId);
		}

		// Stop any scheduled announce handling
		activeTorrentsForAnnounce.keySet().removeIf(hash -> hash.equalsIgnoreCase(normalized));

		// Unregister from seeding
		if (peerServer.unregisterTorrent(normalized)) {
			removed = true;
		}

		// Delete stored torrent file
		if (persistenceService != null) {
			try {
				if (persistenceService.deleteTorrentFile(normalized)) {
					removed = true;
				}
			} catch (IOException e) {
				throw new RuntimeException("Failed to delete torrent file: " + e.getMessage(), e);
			}
		}

		return removed;
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
		// Query peers without event (just getting peer list)
		final var response = trackerClient.announce(torrent, config.getListenPort(), torrent.info().length(), Event.NONE);

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

		// Query peers without event (just getting peer list for status)
		final var response = trackerClient.announce(torrent, config.getListenPort(), 0L, Event.NONE);
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
			// Try to get peers from tracker (initial announcement when download starts)
			java.util.List<java.net.InetSocketAddress> trackerPeers = java.util.List.of();
			try {
				final var response = trackerClient.announce(torrent, config.getListenPort(), torrentInfo.length(), Event.STARTED);
				trackerPeers = response.peers().stream()
					.filter(p -> p.getPort() != config.getListenPort()) // avoid connecting to ourselves
					.toList();
				swarmManager.registerTrackerPeers(infoHashHex, trackerPeers, config.getListenPort());
			} catch (IOException e) {
				// Tracker unavailable - log but continue if we have PEX peers
				System.err.println("Tracker unavailable: " + e.getMessage() + ". Relying on PEX peers.");
			}

			if (trackerPeers.isEmpty() && candidatePeers.isEmpty()) {
				System.out.println("No peers available: tracker returned no peers and no PEX peers known. " +
					"Cannot bootstrap without at least one peer. " +
					"Solution: Use POST /api/torrents/{infoHash}/peers to manually add a peer address, " +
					"or ensure tracker is running and has peers for this torrent.");
				return;
			}
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

			// Inform tracker that we now have the full file (completed download, now seeding)
			// Tracker is optional - don't fail the download if tracker is unavailable
			try {
				trackerClient.announce(torrent, config.getListenPort(), 0L, Event.COMPLETED);
			} catch (IOException e) {
				// Tracker unavailable - log but don't fail the download
				System.err.println("Tracker unavailable when announcing completion: " + e.getMessage() + 
					". Download succeeded regardless.");
			}
			
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
