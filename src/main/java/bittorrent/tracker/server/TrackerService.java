package bittorrent.tracker.server;

import bittorrent.tracker.model.PeerAddress;
import bittorrent.tracker.server.storage.FilePersistenceService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import bittorrent.BitTorrentApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Service
@ConditionalOnProperty(name = "app.mode", havingValue = "tracker", matchIfMissing = false)
public class TrackerService {

    // Map<InfoHash(Hex), Set<PeerAddress>>
    private final Map<String, Set<PeerAddress>> swarms = new ConcurrentHashMap<>();
    private final FilePersistenceService persistenceService;
    private final ScheduledExecutorService saveScheduler = Executors.newScheduledThreadPool(1);
    private ScheduledFuture<?> saveTask;
    
    // Periodic save interval: 30 seconds
    private static final long SAVE_INTERVAL_SECONDS = 30;

    public TrackerService(FilePersistenceService persistenceService) {
        this.persistenceService = persistenceService;
    }

    @PostConstruct
    public void init() {
        Map<String, Set<PeerAddress>> loadedSwarms = persistenceService.load();
        
        // Update lastSeen timestamps for loaded peers to current time
        // This prevents them from being immediately cleaned up
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Set<PeerAddress>> entry : loadedSwarms.entrySet()) {
            Set<PeerAddress> peers = entry.getValue();
            Set<PeerAddress> updatedPeers = new HashSet<>();
            for (PeerAddress peer : peers) {
                // Update lastSeen to current time to prevent immediate cleanup
                updatedPeers.add(new PeerAddress(peer.ip(), peer.port(), peer.peerId(), now));
            }
            loadedSwarms.put(entry.getKey(), updatedPeers);
        }
        
        swarms.putAll(loadedSwarms);
        System.out.println("Loaded " + swarms.size() + " swarms from storage.");
        
        // Schedule periodic saves every 30 seconds
        saveTask = saveScheduler.scheduleAtFixedRate(
            this::saveSwarms,
            SAVE_INTERVAL_SECONDS,
            SAVE_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        );
        
        // Schedule periodic cleanup every 5 minutes (instead of on every announce)
        saveScheduler.scheduleAtFixedRate(
            this::cleanupAllSwarms,
            5,
            5,
            TimeUnit.MINUTES
        );
        
        // Add shutdown hook as backup to ensure save on unexpected termination
        // Note: saveSwarms() will only save if there's actual data, preserving existing file if empty
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            saveSwarms();
            saveScheduler.shutdown();
        }, "TrackerShutdownHook"));
    }
    
    /**
     * Save swarms data to persistent storage.
     * Called periodically and on shutdown.
     * Saves if there's data, or if file is empty/missing (to initialize it).
     * Only skips if swarms is empty AND file has existing data (to preserve it).
     */
    private void saveSwarms() {
        try {
            // Create a deep snapshot of swarms to avoid concurrent modification
            // We need to copy both the map AND the Sets inside to prevent cleanup from modifying them
            Map<String, Set<PeerAddress>> swarmsSnapshot = new ConcurrentHashMap<>();
            
            // Get a snapshot of all swarm keys first to avoid concurrent modification during iteration
            Set<String> swarmKeys = new HashSet<>(swarms.keySet());
            
            for (String infoHashHex : swarmKeys) {
                Set<PeerAddress> peers = swarms.get(infoHashHex);
                if (peers == null) {
                    continue; // Swarm was removed between getting keys and getting the set
                }
                
                // Create a deep copy of the peer set
                Set<PeerAddress> peersCopy;
                synchronized (peers) {
                    peersCopy = new HashSet<>(peers);
                }
                
                // Only add to snapshot if the copy is not empty
                if (!peersCopy.isEmpty()) {
                    swarmsSnapshot.put(infoHashHex, peersCopy);
                    
                    // Debug: log what we're about to save for this swarm
                    System.out.println("TrackerService.saveSwarms: Snapshot swarm " + infoHashHex + 
                        " with " + peersCopy.size() + " peers: " + 
                        peersCopy.stream()
                            .map(p -> p.ip() + ":" + p.port())
                            .collect(java.util.stream.Collectors.joining(", ")));
                }
            }
            
            if (!swarmsSnapshot.isEmpty()) {
                // Always save if we have data
                persistenceService.save(swarmsSnapshot);
                // Count total peers across all swarms for logging
                int totalPeers = swarmsSnapshot.values().stream()
                    .mapToInt(Set::size)
                    .sum();
                System.out.println("Saved tracker data to storage (" + swarmsSnapshot.size() + 
                    " swarms, " + totalPeers + " peers).");
            } else {
                // If swarms is empty, check if file exists and has data
                java.io.File file = new java.io.File("tracker_data/swarms.json");
                if (!file.exists() || file.length() == 0) {
                    // File doesn't exist or is empty - safe to save empty map
                    persistenceService.save(swarmsSnapshot);
                    System.out.println("Saved tracker data to storage (0 swarms - initializing empty file).");
                } else {
                    // File exists and has data - preserve it by skipping save
                    System.out.println("Skipping save - no active swarms but existing data found (preserving " + 
                        file.length() + " bytes).");
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to save tracker data: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @PreDestroy
    public void shutdown() {
        // Cancel periodic save task
        if (saveTask != null) {
            saveTask.cancel(false);
        }
        
        // Shutdown scheduler
        saveScheduler.shutdown();
        try {
            // Wait a short time for any pending saves to complete
            if (!saveScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                saveScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            saveScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // Final save as safety net (only saves if there's data, preserving existing file if empty)
        saveSwarms();
    }

    // Configurable cleanup interval and timeout
    private static final long PEER_TIMEOUT_MS = 30 * 60 * 1000; // 30 minutes

    public void announce(String infoHashHex, String ip, int port, String peerId) {
        swarms.computeIfAbsent(infoHashHex, k -> Collections.synchronizedSet(new HashSet<>()));
        
        Set<PeerAddress> peers = swarms.get(infoHashHex);
        
        // Remove existing entry for this peer if present (to update timestamp/port)
        // PeerAddress is a record, so equality is based on fields.
        // Identify peer by IP + Port only, so multiple clients sharing a peerId
        // (e.g., same implementation) can still be tracked separately.
        synchronized (peers) {
            int beforeSize = peers.size();
            peers.removeIf(p -> p.ip().equals(ip) && p.port() == port);
            peers.add(new PeerAddress(ip, port, peerId, System.currentTimeMillis()));
            int afterSize = peers.size();
            
            if (BitTorrentApplication.DEBUG) {
                System.out.println("Tracker: Added peer " + ip + ":" + port + " for " + infoHashHex + 
                    " (peers: " + beforeSize + " -> " + afterSize + ")");
            }
        }
        
        // Cleanup is now done periodically, not on every announce
        // This prevents peers from being removed immediately after loading from disk
    }

    public List<PeerAddress> getPeers(String infoHashHex) {
        Set<PeerAddress> peers = swarms.get(infoHashHex);
        if (peers == null) {
            return Collections.emptyList();
        }
        
        synchronized (peers) {
            // Filter to only return IPv4 addresses
            return peers.stream()
                .filter(peer -> {
                    try {
                        java.net.InetAddress addr = java.net.InetAddress.getByName(peer.ip());
                        return addr.getAddress().length == 4; // IPv4 addresses are 4 bytes
                    } catch (Exception e) {
                        return false;
                    }
                })
                .collect(Collectors.toList());
        }
    }

    /**
     * Manually register a peer in the tracker (for testing/bootstrap)
     * @param infoHashHex The info hash hex string
     * @param ip Peer IP address
     * @param port Peer port
     * @param peerId Peer ID (optional, defaults to "manual-peer")
     */
    public void registerPeerManually(String infoHashHex, String ip, int port, String peerId) {
        String effectivePeerId = peerId != null && !peerId.isEmpty() ? peerId : "manual-peer";
        announce(infoHashHex, ip, port, effectivePeerId);
    }

    /**
     * Cleanup all swarms - removes peers that haven't been seen in 30 minutes.
     * Called periodically instead of on every announce to prevent premature removal.
     */
    private void cleanupAllSwarms() {
        long now = System.currentTimeMillis();
        Set<String> swarmKeys = new HashSet<>(swarms.keySet());
        
        for (String infoHashHex : swarmKeys) {
            Set<PeerAddress> peers = swarms.get(infoHashHex);
            if (peers == null) continue;
            
            // Remove peers that haven't been seen in 30 minutes
            // Use synchronized block to ensure thread safety
            synchronized (peers) {
                int beforeSize = peers.size();
                peers.removeIf(p -> (now - p.lastSeen()) > PEER_TIMEOUT_MS);
                int afterSize = peers.size();
                
                if (beforeSize != afterSize && BitTorrentApplication.DEBUG) {
                    System.out.println("Tracker cleanup: Removed " + (beforeSize - afterSize) + 
                        " stale peers from swarm " + infoHashHex);
                }
                
                // Only remove the entire swarm entry if it's truly empty
                // This prevents race conditions where a peer might be adding while cleanup runs
                if (peers.isEmpty()) {
                    swarms.remove(infoHashHex);
                }
            }
        }
    }
    
    /**
     * Cleanup a specific swarm (kept for backward compatibility, but not used)
     */
    @Deprecated
    private void cleanup(String infoHashHex) {
        // This method is no longer used - cleanup is now done periodically
        // Keeping it for now in case it's referenced elsewhere
    }
}
