package bittorrent.tracker.server.storage;

import bittorrent.tracker.model.PeerAddress;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.springframework.stereotype.Component;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class FilePersistenceService {

    private static final String STORAGE_FILE = "tracker_data/swarms.json";
    private final Gson gson = new Gson();

    public void save(Map<String, Set<PeerAddress>> swarms) {
        try {
            Path path = Path.of(STORAGE_FILE);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            
            try (Writer writer = new FileWriter(STORAGE_FILE)) {
                gson.toJson(swarms, writer);
            }
        } catch (IOException e) {
            System.err.println("Failed to save tracker data: " + e.getMessage());
        }
    }

    public Map<String, Set<PeerAddress>> load() {
        File file = new File(STORAGE_FILE);
        if (!file.exists()) {
            return new ConcurrentHashMap<>();
        }

        try (Reader reader = new FileReader(file)) {
            Type type = new TypeToken<ConcurrentHashMap<String, Set<PeerAddress>>>(){}.getType();
            Map<String, Set<PeerAddress>> data = gson.fromJson(reader, type);
            return data != null ? data : new ConcurrentHashMap<>();
        } catch (IOException e) {
            System.err.println("Failed to load tracker data: " + e.getMessage());
            return new ConcurrentHashMap<>();
        }
    }
}



