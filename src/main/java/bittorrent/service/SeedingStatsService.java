package bittorrent.service;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks upload statistics for seeding torrents.
 * Similar to DownloadJob but for tracking uploads when serving pieces to peers.
 */
public class SeedingStatsService {
    
    private static final SeedingStatsService INSTANCE = new SeedingStatsService();
    
    public static SeedingStatsService getInstance() {
        return INSTANCE;
    }
    
    // Map<infoHashHex, Map<peerAddress, PeerStats>>
    private final Map<String, Map<String, PeerStats>> seedingStats = new ConcurrentHashMap<>();
    
    /**
     * Get or create PeerStats for a peer connection on a seeding torrent.
     */
    public PeerStats getOrCreatePeerStats(String infoHashHex, InetSocketAddress peerAddress) {
        Map<String, PeerStats> torrentStats = seedingStats.computeIfAbsent(
            infoHashHex, 
            k -> new ConcurrentHashMap<>()
        );
        
        String key = peerAddress.toString();
        return torrentStats.computeIfAbsent(key, k -> new PeerStats(peerAddress));
    }
    
    /**
     * Record that bytes were uploaded to a peer.
     */
    public void recordBytesUploaded(String infoHashHex, InetSocketAddress peerAddress, long bytes) {
        PeerStats stats = getOrCreatePeerStats(infoHashHex, peerAddress);
        stats.addBytesUploaded(bytes);
    }
    
    /**
     * Record that a piece was uploaded to a peer.
     */
    public void recordPieceUploaded(String infoHashHex, InetSocketAddress peerAddress) {
        PeerStats stats = getOrCreatePeerStats(infoHashHex, peerAddress);
        stats.recordPieceUploaded();
    }
    
    /**
     * Get all peer stats for a seeding torrent.
     */
    public Map<String, PeerStats> getTorrentStats(String infoHashHex) {
        Map<String, PeerStats> stats = seedingStats.get(infoHashHex);
        return stats != null ? stats : Collections.emptyMap();
    }
    
    /**
     * Get list of all peer stats for a seeding torrent.
     */
    public List<PeerStats> getTorrentPeerStats(String infoHashHex) {
        Map<String, PeerStats> stats = getTorrentStats(infoHashHex);
        return new ArrayList<>(stats.values());
    }
    
    /**
     * Remove stats for a peer (when peer disconnects).
     */
    public void removePeerStats(String infoHashHex, InetSocketAddress peerAddress) {
        Map<String, PeerStats> torrentStats = seedingStats.get(infoHashHex);
        if (torrentStats != null) {
            torrentStats.remove(peerAddress.toString());
            if (torrentStats.isEmpty()) {
                seedingStats.remove(infoHashHex);
            }
        }
    }
    
    /**
     * Clear all stats for a torrent (when seeding stops).
     */
    public void clearTorrentStats(String infoHashHex) {
        seedingStats.remove(infoHashHex);
    }
}

