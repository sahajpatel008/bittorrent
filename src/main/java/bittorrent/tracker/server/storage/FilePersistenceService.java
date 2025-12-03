package bittorrent.tracker.server.storage;

import bittorrent.tracker.model.PeerAddress;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(name = "app.mode", havingValue = "tracker", matchIfMissing = false)
public class FilePersistenceService {

    private static final String STORAGE_FILE = "tracker_data/swarms.json";
    private final Gson gson = new Gson();

    public void save(Map<String, Set<PeerAddress>> swarms) {
        try {
            Path path = Path.of(STORAGE_FILE);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            
            // Convert Set<PeerAddress> to List<PeerAddress> for proper JSON serialization
            // Gson sometimes has issues with Set serialization, especially with records
            Map<String, List<PeerAddress>> swarmsAsLists = new java.util.HashMap<>();
            for (Map.Entry<String, Set<PeerAddress>> entry : swarms.entrySet()) {
                String infoHashHex = entry.getKey();
                Set<PeerAddress> peerSet = entry.getValue();
                // Create a new list with all peers from the set
                List<PeerAddress> peerList = new java.util.ArrayList<>();
                synchronized (peerSet) {
                    peerList.addAll(peerSet);
                }
                swarmsAsLists.put(infoHashHex, peerList);
                
                // Debug: log what we're about to save for this swarm
                System.out.println("FilePersistenceService: Preparing to save swarm " + infoHashHex + 
                    " with " + peerList.size() + " peers: " + 
                    peerList.stream()
                        .map(p -> p.ip() + ":" + p.port())
                        .collect(java.util.stream.Collectors.joining(", ")));
            }
            
            // Write to a temporary file first, then atomically replace the original
            Path tempFile = Path.of(STORAGE_FILE + ".tmp");
            try (Writer writer = new FileWriter(tempFile.toFile())) {
                gson.toJson(swarmsAsLists, writer);
                writer.flush();
            }
            
            // Verify temp file was created and has content
            if (!Files.exists(tempFile) || Files.size(tempFile) == 0) {
                throw new IOException("Temp file was not created or is empty");
            }
            
            // Verify what was written to temp file
            try (Reader reader = new FileReader(tempFile.toFile())) {
                Type type = new TypeToken<Map<String, List<PeerAddress>>>(){}.getType();
                Map<String, List<PeerAddress>> verify = gson.fromJson(reader, type);
                if (verify != null) {
                    int verifyTotal = verify.values().stream().mapToInt(List::size).sum();
                    System.out.println("FilePersistenceService: Verified temp file contains " + 
                        verify.size() + " swarms with " + verifyTotal + " total peers");
                }
            }
            
            // Atomically replace the original file with the temp file
            try {
                if (!Files.exists(tempFile)) {
                    throw new IOException("Temp file does not exist: " + tempFile);
                }
                Files.move(tempFile, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, 
                           java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                // Fallback: if atomic move is not supported, use regular move
                System.out.println("FilePersistenceService: Atomic move not supported, using regular move");
                if (!Files.exists(tempFile)) {
                    throw new IOException("Temp file does not exist for regular move: " + tempFile);
                }
                Files.move(tempFile, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.NoSuchFileException e) {
                // Temp file was deleted or never created - this shouldn't happen but handle it gracefully
                System.err.println("FilePersistenceService: Temp file missing during move: " + e.getMessage());
                throw new IOException("Temp file was deleted before move operation: " + tempFile, e);
            }
            
            // Verify what was actually written to the final file
            try (Reader reader = new FileReader(path.toFile())) {
                Type type = new TypeToken<Map<String, List<PeerAddress>>>(){}.getType();
                Map<String, List<PeerAddress>> verify = gson.fromJson(reader, type);
                if (verify != null) {
                    int verifyTotal = verify.values().stream().mapToInt(List::size).sum();
                    System.out.println("FilePersistenceService: Verified final file contains " + 
                        verify.size() + " swarms with " + verifyTotal + " total peers");
                    
                    // Log each swarm's peers for debugging
                    for (Map.Entry<String, List<PeerAddress>> entry : verify.entrySet()) {
                        System.out.println("FilePersistenceService: Swarm " + entry.getKey() + 
                            " has " + entry.getValue().size() + " peers: " + 
                            entry.getValue().stream()
                                .map(p -> p.ip() + ":" + p.port())
                                .collect(java.util.stream.Collectors.joining(", ")));
                    }
                } else {
                    System.err.println("FilePersistenceService: WARNING - Verification returned null!");
                }
            } catch (Exception e) {
                System.err.println("FilePersistenceService: Failed to verify saved file: " + e.getMessage());
            }
            
            // Debug: verify what was saved
            int totalPeers = swarmsAsLists.values().stream().mapToInt(List::size).sum();
            System.out.println("FilePersistenceService: Saved " + swarmsAsLists.size() + 
                " swarms with " + totalPeers + " total peers to " + STORAGE_FILE);
        } catch (IOException e) {
            System.err.println("Failed to save tracker data: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public Map<String, Set<PeerAddress>> load() {
        File file = new File(STORAGE_FILE);
        if (!file.exists()) {
            return new ConcurrentHashMap<>();
        }

        try (Reader reader = new FileReader(file)) {
            // Load as Map<String, List<PeerAddress>> since we save as Lists
            Type type = new TypeToken<Map<String, List<PeerAddress>>>(){}.getType();
            Map<String, List<PeerAddress>> data = gson.fromJson(reader, type);
            
            if (data == null) {
                return new ConcurrentHashMap<>();
            }
            
            // Convert List<PeerAddress> back to Set<PeerAddress>
            Map<String, Set<PeerAddress>> result = new ConcurrentHashMap<>();
            for (Map.Entry<String, List<PeerAddress>> entry : data.entrySet()) {
                result.put(entry.getKey(), new HashSet<>(entry.getValue()));
            }
            
            return result;
        } catch (IOException e) {
            System.err.println("Failed to load tracker data: " + e.getMessage());
            return new ConcurrentHashMap<>();
        }
    }
}



