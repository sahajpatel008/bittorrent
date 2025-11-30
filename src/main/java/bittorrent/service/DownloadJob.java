package bittorrent.service;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import bittorrent.peer.Peer;

/**
 * Represents an asynchronous download job with enhanced tracking.
 */
public class DownloadJob {
    public enum Status {
        PENDING,
        DOWNLOADING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    private final String jobId;
    private final String infoHashHex;
    private final String fileName;
    private Status status;
    private File downloadedFile;
    private String errorMessage;
    private int totalPieces;
    private int completedPieces;
    private List<Peer> activePeers;
    private CompletableFuture<File> future;
    
    // Enhanced tracking: Map<peerAddress, PeerStats>
    private final Map<String, PeerStats> peerStats = new ConcurrentHashMap<>();
    
    // Track which peer downloaded which piece: Map<pieceIndex, peerAddress>
    private final Map<Integer, String> pieceSource = new ConcurrentHashMap<>();
    
    private long startTime;
    private volatile long lastUpdateTime;

    public DownloadJob(String infoHashHex, String fileName) {
        this(UUID.randomUUID().toString(), infoHashHex, fileName);
    }
    
    /**
     * Constructor for resuming a persisted download job
     */
    public DownloadJob(String jobId, String infoHashHex, String fileName) {
        this.jobId = jobId;
        this.infoHashHex = infoHashHex;
        this.fileName = fileName;
        this.status = Status.PENDING;
        this.totalPieces = 0;
        this.completedPieces = 0;
        this.startTime = System.currentTimeMillis();
        this.lastUpdateTime = System.currentTimeMillis();
    }

    public String getJobId() {
        return jobId;
    }

    public String getInfoHashHex() {
        return infoHashHex;
    }

    public String getFileName() {
        return fileName;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public File getDownloadedFile() {
        return downloadedFile;
    }

    public void setDownloadedFile(File downloadedFile) {
        this.downloadedFile = downloadedFile;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public int getTotalPieces() {
        return totalPieces;
    }

    public void setTotalPieces(int totalPieces) {
        this.totalPieces = totalPieces;
    }

    public int getCompletedPieces() {
        return completedPieces;
    }

    public void setCompletedPieces(int completedPieces) {
        this.completedPieces = completedPieces;
    }

    public double getProgress() {
        if (totalPieces == 0) {
            return 0.0;
        }
        return (double) completedPieces / totalPieces * 100.0;
    }

    public List<Peer> getActivePeers() {
        return activePeers;
    }

    public void setActivePeers(List<Peer> activePeers) {
        this.activePeers = activePeers;
    }

    public CompletableFuture<File> getFuture() {
        return future;
    }

    public void setFuture(CompletableFuture<File> future) {
        this.future = future;
    }
    
    public Map<String, PeerStats> getPeerStats() {
        return peerStats;
    }
    
    public PeerStats getOrCreatePeerStats(java.net.InetSocketAddress address) {
        String key = address.toString();
        return peerStats.computeIfAbsent(key, k -> new PeerStats(address));
    }
    
    public void recordPieceDownloaded(int pieceIndex, java.net.InetSocketAddress peerAddress, long pieceSize) {
        PeerStats stats = getOrCreatePeerStats(peerAddress);
        stats.recordPieceDownloaded(pieceIndex);
        stats.addBytesDownloaded(pieceSize);
        pieceSource.put(pieceIndex, peerAddress.toString());
        lastUpdateTime = System.currentTimeMillis();
    }
    
    public Map<Integer, String> getPieceSource() {
        return pieceSource;
    }
    
    public long getStartTime() {
        return startTime;
    }
    
    public long getLastUpdateTime() {
        return lastUpdateTime;
    }
    
    public double getOverallDownloadSpeed() {
        long elapsed = System.currentTimeMillis() - startTime;
        if (elapsed == 0) return 0.0;
        long totalBytes = peerStats.values().stream()
            .mapToLong(PeerStats::getBytesDownloaded)
            .sum();
        return (totalBytes * 1000.0) / elapsed; // bytes per second
    }
}

