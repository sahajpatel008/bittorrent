package bittorrent.service.storage;

import bittorrent.BitTorrentApplication;
import bittorrent.config.BitTorrentConfig;
import bittorrent.service.DownloadJob;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for persisting torrent files and download job states
 */
@Component
public class TorrentPersistenceService {

    private final String storageDir;
    private final String torrentsDir;
    private final String downloadJobsFile;
    private final String seedingTorrentsFile;
    
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    @Autowired
    public TorrentPersistenceService(BitTorrentConfig config) {
        int listenPort = config.getListenPort();
        // Use peer_data/{port}/ directory in the project repo
        this.storageDir = "peer_data/" + listenPort;
        this.torrentsDir = this.storageDir + "/torrents";
        this.downloadJobsFile = this.storageDir + "/download_jobs.json";
        this.seedingTorrentsFile = this.storageDir + "/seeding_torrents.json";
        
        // Create directories if they don't exist
        try {
            Path dirPath = Path.of(this.storageDir);
            if (!Files.exists(dirPath)) {
                Files.createDirectories(dirPath);
            }
            Path torrentsPath = Path.of(this.torrentsDir);
            if (!Files.exists(torrentsPath)) {
                Files.createDirectories(torrentsPath);
            }
        } catch (IOException e) {
            System.err.println("Failed to create storage directories: " + e.getMessage());
        }
    }

    /**
     * Save a torrent file permanently
     * @param infoHashHex The info hash of the torrent
     * @param torrentFile The torrent file to save
     * @return Path to saved torrent file
     */
    public String saveTorrentFile(String infoHashHex, File torrentFile) throws IOException {
        Path torrentsPath = Path.of(torrentsDir);
        Path targetPath = torrentsPath.resolve(infoHashHex + ".torrent");
        
        Files.copy(torrentFile.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING);
        return targetPath.toString();
    }

    /**
     * Load a torrent file by info hash
     * @param infoHashHex The info hash of the torrent
     * @return Path to torrent file, or null if not found
     */
    public String getTorrentFilePath(String infoHashHex) {
        Path torrentPath = Path.of(torrentsDir, infoHashHex + ".torrent");
        if (Files.exists(torrentPath)) {
            return torrentPath.toString();
        }
        return null;
    }

    /**
     * Check if a torrent file exists
     */
    public boolean hasTorrentFile(String infoHashHex) {
        Path torrentPath = Path.of(torrentsDir, infoHashHex + ".torrent");
        return Files.exists(torrentPath);
    }

    /**
     * Delete a stored torrent file
     * @return true if a file was removed
     */
    public boolean deleteTorrentFile(String infoHashHex) throws IOException {
        Path torrentPath = Path.of(torrentsDir, infoHashHex + ".torrent");
        return Files.deleteIfExists(torrentPath);
    }

    /**
     * Save download jobs state
     */
    public void saveDownloadJobs(Map<String, DownloadJob> jobs) {
        try {
            // Convert to serializable format
            List<DownloadJobState> states = new ArrayList<>();
            for (DownloadJob job : jobs.values()) {
                if (job.getStatus() == DownloadJob.Status.DOWNLOADING || 
                    job.getStatus() == DownloadJob.Status.PENDING) {
                    // Only save active jobs
                    DownloadJobState state = new DownloadJobState();
                    state.jobId = job.getJobId();
                    state.infoHashHex = job.getInfoHashHex();
                    state.fileName = job.getFileName();
                    state.status = job.getStatus().name();
                    state.totalPieces = job.getTotalPieces();
                    state.completedPieces = job.getCompletedPieces();
                    state.downloadedFilePath = job.getDownloadedFile() != null ? 
                        job.getDownloadedFile().getAbsolutePath() : null;
                    states.add(state);
                }
            }
            
            try (Writer writer = new FileWriter(downloadJobsFile)) {
                gson.toJson(states, writer);
            }
        } catch (IOException e) {
            System.err.println("Failed to save download jobs: " + e.getMessage());
            if (BitTorrentApplication.DEBUG) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Load download jobs state
     */
    public List<DownloadJobState> loadDownloadJobs() {
        File file = new File(downloadJobsFile);
        if (!file.exists()) {
            return new ArrayList<>();
        }

        try (Reader reader = new FileReader(file)) {
            TypeToken<List<DownloadJobState>> typeToken = new TypeToken<List<DownloadJobState>>(){};
            List<DownloadJobState> states = gson.fromJson(reader, typeToken.getType());
            return states != null ? states : new ArrayList<>();
        } catch (IOException e) {
            System.err.println("Failed to load download jobs: " + e.getMessage());
            if (BitTorrentApplication.DEBUG) {
                e.printStackTrace();
            }
            return new ArrayList<>();
        }
    }

    /**
     * Save seeding torrents state
     */
    public void saveSeedingTorrents(Map<String, SeedingTorrentState> seedingTorrents) {
        try {
            List<SeedingTorrentState> states = new ArrayList<>(seedingTorrents.values());
            try (Writer writer = new FileWriter(seedingTorrentsFile)) {
                gson.toJson(states, writer);
            }
        } catch (IOException e) {
            System.err.println("Failed to save seeding torrents: " + e.getMessage());
            if (BitTorrentApplication.DEBUG) {
                e.printStackTrace();
            }
        }
    }
    
    /**
     * Save seeding torrents state (overload for List)
     */
    public void saveSeedingTorrents(List<SeedingTorrentState> states) {
        try {
            try (Writer writer = new FileWriter(seedingTorrentsFile)) {
                gson.toJson(states, writer);
            }
        } catch (IOException e) {
            System.err.println("Failed to save seeding torrents: " + e.getMessage());
            if (BitTorrentApplication.DEBUG) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Load seeding torrents state
     */
    public List<SeedingTorrentState> loadSeedingTorrents() {
        File file = new File(seedingTorrentsFile);
        if (!file.exists()) {
            return new ArrayList<>();
        }

        try (Reader reader = new FileReader(file)) {
            TypeToken<List<SeedingTorrentState>> typeToken = new TypeToken<List<SeedingTorrentState>>(){};
            List<SeedingTorrentState> states = gson.fromJson(reader, typeToken.getType());
            return states != null ? states : new ArrayList<>();
        } catch (IOException e) {
            System.err.println("Failed to load seeding torrents: " + e.getMessage());
            if (BitTorrentApplication.DEBUG) {
                e.printStackTrace();
            }
            return new ArrayList<>();
        }
    }

    /**
     * Serializable state for download jobs
     */
    public static class DownloadJobState {
        public String jobId;
        public String infoHashHex;
        public String fileName;
        public String status;
        public int totalPieces;
        public int completedPieces;
        public String downloadedFilePath;
    }

    /**
     * Serializable state for seeding torrents
     */
    public static class SeedingTorrentState {
        public String infoHashHex;
        public String torrentFilePath;
        public String dataFilePath;
        public String fileName;
    }
}
