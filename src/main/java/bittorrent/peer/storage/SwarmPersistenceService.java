package bittorrent.peer.storage;

import bittorrent.BitTorrentApplication;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistence service for SwarmManager to save/load known peers across restarts.
 */
public class SwarmPersistenceService {

    private final String storageDir;
    private final String storageFile;
    private final Gson gson = new Gson();

    public SwarmPersistenceService(int listenPort) {
        this.storageDir = System.getProperty("user.home") + "/.bittorrent-peer-" + listenPort;
        this.storageFile = this.storageDir + "/known_peers.json";
    }

    /**
     * Data structure for serialization
     */
    private static class SwarmData {
        String host;
        int port;
        
        SwarmData() {} // For Gson
        
        SwarmData(String host, int port) {
            this.host = host;
            this.port = port;
        }
        
        InetSocketAddress toAddress() {
            return new InetSocketAddress(host, port);
        }
    }
    
    private static class SwarmStateData {
        List<SwarmData> knownPeers = new ArrayList<>();
        
        SwarmStateData() {} // For Gson
    }

    /**
     * Save known peers to disk
     * @param swarms Map<infoHashHex, Set<InetSocketAddress>>
     */
    public void save(Map<String, Set<InetSocketAddress>> swarms) {
        try {
            Path path = Path.of(storageFile);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            
            // Convert to serializable format
            Map<String, SwarmStateData> data = new HashMap<>();
            for (Map.Entry<String, Set<InetSocketAddress>> entry : swarms.entrySet()) {
                SwarmStateData stateData = new SwarmStateData();
                for (InetSocketAddress addr : entry.getValue()) {
                    stateData.knownPeers.add(new SwarmData(addr.getHostString(), addr.getPort()));
                }
                data.put(entry.getKey(), stateData);
            }
            
            try (Writer writer = new FileWriter(storageFile)) {
                gson.toJson(data, writer);
            }
            
            if (BitTorrentApplication.DEBUG) {
                System.out.println("Saved " + swarms.size() + " swarms to " + storageFile);
            }
        } catch (IOException e) {
            System.err.println("Failed to save swarm data: " + e.getMessage());
        }
    }

    /**
     * Load known peers from disk
     * @return Map<infoHashHex, Set<InetSocketAddress>>
     */
    public Map<String, Set<InetSocketAddress>> load() {
        File file = new File(storageFile);
        if (!file.exists()) {
            return new ConcurrentHashMap<>();
        }

        try (Reader reader = new FileReader(file)) {
            Type type = new TypeToken<Map<String, SwarmStateData>>(){}.getType();
            Map<String, SwarmStateData> data = gson.fromJson(reader, type);
            
            if (data == null) {
                return new ConcurrentHashMap<>();
            }
            
            // Convert back to InetSocketAddress sets
            Map<String, Set<InetSocketAddress>> swarms = new ConcurrentHashMap<>();
            for (Map.Entry<String, SwarmStateData> entry : data.entrySet()) {
                Set<InetSocketAddress> peers = ConcurrentHashMap.newKeySet();
                for (SwarmData peerData : entry.getValue().knownPeers) {
                    try {
                        peers.add(peerData.toAddress());
                    } catch (Exception e) {
                        // Skip invalid addresses
                        System.err.println("Skipping invalid peer address: " + peerData.host + ":" + peerData.port);
                    }
                }
                swarms.put(entry.getKey(), peers);
            }
            
            if (BitTorrentApplication.DEBUG) {
                System.out.println("Loaded " + swarms.size() + " swarms from " + storageFile);
            }
            
            return swarms;
        } catch (IOException e) {
            System.err.println("Failed to load swarm data: " + e.getMessage());
            return new ConcurrentHashMap<>();
        }
    }
}

