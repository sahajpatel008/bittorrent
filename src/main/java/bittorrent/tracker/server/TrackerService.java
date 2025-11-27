package bittorrent.tracker.server;

import bittorrent.tracker.model.PeerAddress;
import bittorrent.tracker.server.storage.FilePersistenceService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class TrackerService {

    // Map<InfoHash(Hex), Set<PeerAddress>>
    private final Map<String, Set<PeerAddress>> swarms = new ConcurrentHashMap<>();
    private final FilePersistenceService persistenceService;

    public TrackerService(FilePersistenceService persistenceService) {
        this.persistenceService = persistenceService;
    }

    @PostConstruct
    public void init() {
        swarms.putAll(persistenceService.load());
        System.out.println("Loaded " + swarms.size() + " swarms from storage.");
    }

    @PreDestroy
    public void shutdown() {
        persistenceService.save(swarms);
        System.out.println("Saved tracker data to storage.");
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
        peers.removeIf(p -> p.ip().equals(ip) && p.port() == port);

        peers.add(new PeerAddress(ip, port, peerId, System.currentTimeMillis()));
        
        // Trigger cleanup (could be async or periodic)
        cleanup(infoHashHex);
        
        // Simple write-behind or periodic save could be better, but saving on every announce 
        // ensures data safety at cost of IO. For now, let's rely on shutdown hook 
        // or maybe add a scheduled task.
    }

    public List<PeerAddress> getPeers(String infoHashHex) {
        Set<PeerAddress> peers = swarms.get(infoHashHex);
        if (peers == null) {
            return Collections.emptyList();
        }
        
        synchronized (peers) {
             return new ArrayList<>(peers);
        }
    }

    private void cleanup(String infoHashHex) {
        Set<PeerAddress> peers = swarms.get(infoHashHex);
        if (peers == null) return;

        long now = System.currentTimeMillis();
        peers.removeIf(p -> (now - p.lastSeen()) > PEER_TIMEOUT_MS);
        
        if (peers.isEmpty()) {
            swarms.remove(infoHashHex);
        }
    }
}
