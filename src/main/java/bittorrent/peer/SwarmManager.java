package bittorrent.peer;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import bittorrent.BitTorrentApplication;

/**
 * Simple in-memory swarm manager that tracks known and active peers per
 * torrent (info hash).
 *
 * This is intentionally lightweight and not thread-perfect; it is good
 * enough for local testing with a small number of peers.
 */
public class SwarmManager {

    private static final SwarmManager INSTANCE = new SwarmManager();

    public static SwarmManager getInstance() {
        return INSTANCE;
    }

    private static class SwarmState {
        final Set<InetSocketAddress> knownPeers = ConcurrentHashMap.newKeySet();
        final Set<InetSocketAddress> activePeers = ConcurrentHashMap.newKeySet();
        final Set<InetSocketAddress> droppedPeers = ConcurrentHashMap.newKeySet();
        // Track recently sent peers to avoid sending duplicates too quickly
        final Map<InetSocketAddress, Long> lastSentTime = new ConcurrentHashMap<>();
    }

    // Map<infoHashHex, SwarmState>
    private final Map<String, SwarmState> swarms = new ConcurrentHashMap<>();

    private SwarmState getOrCreate(String infoHashHex) {
        return swarms.computeIfAbsent(infoHashHex, k -> new SwarmState());
    }

    public void registerTrackerPeers(String infoHashHex, List<InetSocketAddress> peers, int selfPort) {
        if (peers == null || peers.isEmpty()) {
            return;
        }
        SwarmState state = getOrCreate(infoHashHex);
        int added = 0;
        for (InetSocketAddress addr : peers) {
            if (addr.getPort() == selfPort) {
                continue; // avoid ourselves
            }
            if (state.knownPeers.add(addr)) {
                added++;
                // Remove from dropped if it was there
                state.droppedPeers.remove(addr);
            }
        }
        if (BitTorrentApplication.DEBUG && added > 0) {
            System.err.printf("SwarmManager[%s]: tracker added %d new peers (total: %d)%n", 
                infoHashHex, added, state.knownPeers.size());
        }
    }

    public void onPexPeersDiscovered(String infoHashHex, List<InetSocketAddress> peers) {
        if (peers == null || peers.isEmpty()) {
            return;
        }
        SwarmState state = getOrCreate(infoHashHex);
        int added = 0;
        for (InetSocketAddress addr : peers) {
            if (state.knownPeers.add(addr)) {
                added++;
                // Remove from dropped if it was there
                state.droppedPeers.remove(addr);
            }
        }
        if (BitTorrentApplication.DEBUG && added > 0) {
            System.err.printf("SwarmManager[%s]: PEX added %d new peers (total: %d)%n", 
                infoHashHex, added, state.knownPeers.size());
        }
    }

    /**
     * Checks if a peer is already known in the swarm.
     */
    public boolean isPeerKnown(String infoHashHex, InetSocketAddress address) {
        SwarmState state = swarms.get(infoHashHex);
        return state != null && state.knownPeers.contains(address);
    }

    public void registerActivePeer(String infoHashHex, InetSocketAddress address) {
        SwarmState state = getOrCreate(infoHashHex);
        state.activePeers.add(address);
        // Remove from dropped if it was there
        state.droppedPeers.remove(address);
    }

    public void unregisterActivePeer(String infoHashHex, InetSocketAddress address) {
        SwarmState state = swarms.get(infoHashHex);
        if (state != null) {
            state.activePeers.remove(address);
            // Mark as dropped if it was known
            if (state.knownPeers.contains(address)) {
                state.droppedPeers.add(address);
            }
        }
    }

    /**
     * Returns up to {@code max} peers from the known set that are not
     * currently active.
     */
    public List<InetSocketAddress> acquirePeers(String infoHashHex, int max) {
        SwarmState state = swarms.get(infoHashHex);
        if (state == null || state.knownPeers.isEmpty() || max <= 0) {
            return Collections.emptyList();
        }

        int count = 0;
        var result = new ArrayList<InetSocketAddress>(max);

        Iterator<InetSocketAddress> it = state.knownPeers.iterator();
        while (it.hasNext() && count < max) {
            InetSocketAddress addr = it.next();
            if (!state.activePeers.contains(addr)) {
                result.add(addr);
                count++;
            }
        }

        return result;
    }

    /**
     * Gets peers for PEX update, excluding the current peer and self.
     * Also excludes recently sent peers to avoid spam.
     * 
     * @param infoHashHex The info hash hex string
     * @param excludeAddresses Addresses to exclude (current peer, self, etc.)
     * @param max Maximum number of peers to return
     * @return List of peer addresses suitable for PEX
     */
    public List<InetSocketAddress> getPeersForPex(String infoHashHex, 
                                                   Set<InetSocketAddress> excludeAddresses, 
                                                   int max) {
        SwarmState state = swarms.get(infoHashHex);
        if (state == null || state.knownPeers.isEmpty() || max <= 0) {
            return Collections.emptyList();
        }

        long now = System.currentTimeMillis();
        long RECENT_THRESHOLD = 60_000; // Don't send same peer within 60 seconds

        List<InetSocketAddress> candidates = state.knownPeers.stream()
            .filter(addr -> !state.activePeers.contains(addr)) // Not currently active
            .filter(addr -> excludeAddresses == null || !excludeAddresses.contains(addr)) // Not excluded
            .filter(addr -> {
                Long lastSent = state.lastSentTime.get(addr);
                return lastSent == null || (now - lastSent) > RECENT_THRESHOLD;
            })
            .limit(max)
            .collect(Collectors.toList());

        // Update last sent time
        for (InetSocketAddress addr : candidates) {
            state.lastSentTime.put(addr, now);
        }

        return candidates;
    }

    /**
     * Gets dropped peers for PEX update.
     */
    public List<InetSocketAddress> getDroppedPeers(String infoHashHex, int max) {
        SwarmState state = swarms.get(infoHashHex);
        if (state == null || state.droppedPeers.isEmpty() || max <= 0) {
            return Collections.emptyList();
        }

        List<InetSocketAddress> result = new ArrayList<>(Math.min(max, state.droppedPeers.size()));
        Iterator<InetSocketAddress> it = state.droppedPeers.iterator();
        int count = 0;
        while (it.hasNext() && count < max) {
            result.add(it.next());
            count++;
        }

        // Clear dropped peers after sending (they've been communicated)
        state.droppedPeers.clear();

        return result;
    }

    /**
     * Clears dropped peers for a swarm (e.g., after sending PEX update).
     */
    public void clearDroppedPeers(String infoHashHex) {
        SwarmState state = swarms.get(infoHashHex);
        if (state != null) {
            state.droppedPeers.clear();
        }
    }
}


