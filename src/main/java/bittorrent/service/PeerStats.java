package bittorrent.service;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks statistics for a single peer connection
 */
public class PeerStats {
    private final InetSocketAddress address;
    private final AtomicLong bytesDownloaded = new AtomicLong(0);
    private final AtomicLong bytesUploaded = new AtomicLong(0);
    private final AtomicLong piecesDownloaded = new AtomicLong(0);
    private final AtomicLong piecesUploaded = new AtomicLong(0);
    private final Map<Integer, Long> pieceDownloadTimes = new ConcurrentHashMap<>(); // pieceIndex -> timestamp
    private long connectionStartTime;
    private volatile long lastActivityTime;
    private volatile boolean isChoked = true;
    private volatile boolean isInterested = false;
    private volatile boolean peerChoking = true;
    private volatile boolean peerInterested = false;
    
    public PeerStats(InetSocketAddress address) {
        this.address = address;
        this.connectionStartTime = System.currentTimeMillis();
        this.lastActivityTime = System.currentTimeMillis();
    }
    
    public InetSocketAddress getAddress() {
        return address;
    }
    
    public long getBytesDownloaded() {
        return bytesDownloaded.get();
    }
    
    public long getBytesUploaded() {
        return bytesUploaded.get();
    }
    
    public long getPiecesDownloaded() {
        return piecesDownloaded.get();
    }
    
    public long getPiecesUploaded() {
        return piecesUploaded.get();
    }
    
    public void addBytesDownloaded(long bytes) {
        bytesDownloaded.addAndGet(bytes);
        lastActivityTime = System.currentTimeMillis();
    }
    
    public void addBytesUploaded(long bytes) {
        bytesUploaded.addAndGet(bytes);
        lastActivityTime = System.currentTimeMillis();
    }
    
    public void recordPieceDownloaded(int pieceIndex) {
        piecesDownloaded.incrementAndGet();
        pieceDownloadTimes.put(pieceIndex, System.currentTimeMillis());
        lastActivityTime = System.currentTimeMillis();
    }
    
    public void recordPieceUploaded() {
        piecesUploaded.incrementAndGet();
        lastActivityTime = System.currentTimeMillis();
    }
    
    public List<Integer> getDownloadedPieces() {
        return pieceDownloadTimes.keySet().stream().sorted().toList();
    }
    
    public double getDownloadSpeed() {
        long elapsed = System.currentTimeMillis() - connectionStartTime;
        if (elapsed == 0) return 0.0;
        return (bytesDownloaded.get() * 1000.0) / elapsed; // bytes per second
    }
    
    public double getUploadSpeed() {
        long elapsed = System.currentTimeMillis() - connectionStartTime;
        if (elapsed == 0) return 0.0;
        return (bytesUploaded.get() * 1000.0) / elapsed; // bytes per second
    }
    
    public long getConnectionDuration() {
        return System.currentTimeMillis() - connectionStartTime;
    }
    
    public long getLastActivityTime() {
        return lastActivityTime;
    }
    
    public boolean isChoked() {
        return isChoked;
    }
    
    public void setChoked(boolean choked) {
        this.isChoked = choked;
    }
    
    public boolean isInterested() {
        return isInterested;
    }
    
    public void setInterested(boolean interested) {
        this.isInterested = interested;
    }
    
    public boolean isPeerChoking() {
        return peerChoking;
    }
    
    public void setPeerChoking(boolean peerChoking) {
        this.peerChoking = peerChoking;
    }
    
    public boolean isPeerInterested() {
        return peerInterested;
    }
    
    public void setPeerInterested(boolean peerInterested) {
        this.peerInterested = peerInterested;
    }
}

