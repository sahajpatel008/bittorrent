package bittorrent.peer;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import bittorrent.BitTorrentApplication;

/**
 * Manages active peer connections per torrent and provides methods to
 * broadcast PEX updates to all connected peers.
 */
public class PeerConnectionManager {

    private static final PeerConnectionManager INSTANCE = new PeerConnectionManager();

    public static PeerConnectionManager getInstance() {
        return INSTANCE;
    }

    // Map<infoHashHex, List<Peer>>
    private final Map<String, List<Peer>> activeConnections = new ConcurrentHashMap<>();

    /**
     * Registers a peer connection for a torrent.
     */
    public void registerConnection(String infoHashHex, Peer peer) {
        activeConnections.computeIfAbsent(infoHashHex, k -> Collections.synchronizedList(new ArrayList<>()))
            .add(peer);
        
        if (BitTorrentApplication.DEBUG) {
            System.err.printf("PeerConnectionManager[%s]: registered connection, total: %d%n",
                infoHashHex, activeConnections.get(infoHashHex).size());
        }
    }

    /**
     * Unregisters a peer connection for a torrent.
     */
    public void unregisterConnection(String infoHashHex, Peer peer) {
        List<Peer> peers = activeConnections.get(infoHashHex);
        if (peers != null) {
            peers.remove(peer);
            if (peers.isEmpty()) {
                activeConnections.remove(infoHashHex);
            }
        }
    }

    /**
     * Gets all active peer connections for a torrent.
     */
    public List<Peer> getConnections(String infoHashHex) {
        List<Peer> peers = activeConnections.get(infoHashHex);
        return peers != null ? new ArrayList<>(peers) : Collections.emptyList();
    }

    /**
     * Broadcasts a PEX update to all connected peers for a torrent,
     * excluding the sender peer.
     */
    public void broadcastPexUpdate(String infoHashHex, Peer sender, 
                                   List<InetSocketAddress> added, 
                                   List<InetSocketAddress> dropped) {
        List<Peer> peers = activeConnections.get(infoHashHex);
        if (peers == null || peers.isEmpty()) {
            return;
        }

        // Filter out closed peers and the sender
        List<Peer> recipients = peers.stream()
            .filter(p -> p != sender)
            .filter(p -> !p.isClosed())
            .collect(Collectors.toList());

        if (recipients.isEmpty()) {
            return;
        }

        if (BitTorrentApplication.DEBUG) {
            System.err.printf("PeerConnectionManager[%s]: broadcasting PEX to %d peers (added: %d, dropped: %d)%n",
                infoHashHex, recipients.size(), added.size(), dropped.size());
        }

        // Send PEX update to each recipient
        for (Peer peer : recipients) {
            try {
                peer.sendPexUpdate(added, dropped);
            } catch (Exception e) {
                if (BitTorrentApplication.DEBUG) {
                    System.err.printf("PeerConnectionManager: failed to send PEX to peer: %s%n", e.getMessage());
                }
            }
        }
    }

    /**
     * Broadcasts a PEX update to all connected peers when new peers are discovered.
     */
    public void broadcastNewPeers(String infoHashHex, List<InetSocketAddress> newPeers) {
        if (newPeers == null || newPeers.isEmpty()) {
            return;
        }

        List<Peer> peers = getConnections(infoHashHex);
        if (peers.isEmpty()) {
            return;
        }

        // Create exclusion set: all currently connected peer addresses
        Set<InetSocketAddress> excludeAddresses = peers.stream()
            .map(Peer::getRemoteAddress)
            .filter(addr -> addr != null)
            .collect(Collectors.toSet());

        // Filter out peers we're already connected to
        List<InetSocketAddress> filteredPeers = newPeers.stream()
            .filter(addr -> !excludeAddresses.contains(addr))
            .collect(Collectors.toList());

        if (filteredPeers.isEmpty()) {
            return;
        }

        // Broadcast to all connected peers
        for (Peer peer : peers) {
            try {
                if (!peer.isClosed()) {
                    peer.sendPexUpdate(filteredPeers, Collections.emptyList());
                }
            } catch (Exception e) {
                if (BitTorrentApplication.DEBUG) {
                    System.err.printf("PeerConnectionManager: failed to broadcast PEX: %s%n", e.getMessage());
                }
            }
        }
    }
}

