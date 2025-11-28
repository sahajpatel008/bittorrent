package bittorrent.peer;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        final Set<InetSocketAddress> knownPeers = new HashSet<>();
        final Set<InetSocketAddress> activePeers = new HashSet<>();
    }

    // Map<infoHashHex, SwarmState>
    private final Map<String, SwarmState> swarms = new HashMap<>();

    private SwarmState getOrCreate(String infoHashHex) {
        return swarms.computeIfAbsent(infoHashHex, k -> new SwarmState());
    }

    public void registerTrackerPeers(String infoHashHex, List<InetSocketAddress> peers, int selfPort) {
        if (peers == null || peers.isEmpty()) {
            return;
        }
        SwarmState state = getOrCreate(infoHashHex);
        for (InetSocketAddress addr : peers) {
            if (addr.getPort() == selfPort) {
                continue; // avoid ourselves
            }
            state.knownPeers.add(addr);
        }
        if (BitTorrentApplication.DEBUG) {
            System.err.printf("SwarmManager[%s]: tracker added %d peers%n", infoHashHex, peers.size());
        }
    }

    public void onPexPeersDiscovered(String infoHashHex, List<InetSocketAddress> peers) {
        if (peers == null || peers.isEmpty()) {
            return;
        }
        SwarmState state = getOrCreate(infoHashHex);
        state.knownPeers.addAll(peers);
        if (BitTorrentApplication.DEBUG) {
            System.err.printf("SwarmManager[%s]: PEX added %d peers%n", infoHashHex, peers.size());
        }
    }

    public void registerActivePeer(String infoHashHex, InetSocketAddress address) {
        SwarmState state = getOrCreate(infoHashHex);
        state.activePeers.add(address);
    }

    public void unregisterActivePeer(String infoHashHex, InetSocketAddress address) {
        SwarmState state = swarms.get(infoHashHex);
        if (state != null) {
            state.activePeers.remove(address);
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
        var result = new java.util.ArrayList<InetSocketAddress>(max);

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
}


