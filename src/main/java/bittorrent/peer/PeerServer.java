package bittorrent.peer;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import bittorrent.peer.protocol.Message;
import bittorrent.peer.protocol.MetadataMessage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import bittorrent.BitTorrentApplication;
import bittorrent.service.storage.TorrentPersistenceService;
import bittorrent.Main;
import bittorrent.config.BitTorrentConfig;
import bittorrent.torrent.TorrentInfo;

@Component
public class PeerServer {

    private static final byte[] PROTOCOL_BYTES = "BitTorrent protocol".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PADDING_8 = new byte[8];
    // PADDING with extension protocol bit set (bit 5 = 0x10)
    private static final byte[] PADDING_EXTENSIONS = { 0, 0, 0, 0, 0, 0x10, 0, 0 };

    private final BitTorrentConfig config;
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final Map<String, TorrentInfo> activeTorrents = new ConcurrentHashMap<>();
    private final Map<String, File> torrentFiles = new ConcurrentHashMap<>();
    
    private ServerSocket serverSocket;
    private boolean running = false;
    
    @Autowired(required = false)
    private TorrentPersistenceService persistenceService;

    public PeerServer(BitTorrentConfig config) {
        this.config = config;
    }

    public void start() {
        if (running) {
            return;
        }
        
        try {
            // Bind to IPv4 only (0.0.0.0) to avoid IPv6 issues
            serverSocket = new ServerSocket(config.getListenPort(), 50, 
                java.net.InetAddress.getByAddress(new byte[]{0, 0, 0, 0}));
            running = true;
            System.out.println("PeerServer listening on port " + config.getListenPort() + " (IPv4 only)");
            
            Thread acceptThread = new Thread(this::acceptLoop);
            acceptThread.setName("PeerServer-Accept");
            acceptThread.start();
            
        } catch (IOException e) {
            System.err.println("Failed to start PeerServer: " + e.getMessage());
        }
    }

    public void registerTorrent(TorrentInfo torrentInfo, File file) {
        String infoHashHex = Main.HEX_FORMAT.formatHex(torrentInfo.hash()).toLowerCase();
        activeTorrents.put(infoHashHex, torrentInfo);
        torrentFiles.put(infoHashHex, file);
        System.out.println("Registered torrent for seeding: " + infoHashHex);
        
        // Save state
        saveSeedingTorrents();
    }
    
    /**
     * Save seeding torrents state
     */
    public void saveSeedingTorrents() {
        if (persistenceService == null) return;
        
        Map<String, TorrentPersistenceService.SeedingTorrentState> states = new ConcurrentHashMap<>();
        for (Map.Entry<String, TorrentInfo> entry : activeTorrents.entrySet()) {
            String infoHashHex = entry.getKey();
            File dataFile = torrentFiles.get(infoHashHex);
            if (dataFile != null) {
                TorrentPersistenceService.SeedingTorrentState state = 
                    new TorrentPersistenceService.SeedingTorrentState();
                state.infoHashHex = infoHashHex;
                state.dataFilePath = dataFile.getAbsolutePath();
                state.fileName = dataFile.getName();
                
                // Get torrent file path
                String torrentPath = persistenceService.getTorrentFilePath(infoHashHex);
                state.torrentFilePath = torrentPath;
                
                states.put(infoHashHex, state);
            }
        }
        
        persistenceService.saveSeedingTorrents(states);
    }
    
    /**
     * Load persisted seeding torrents on startup
     */
    public void loadPersistedSeedingTorrents() {
        if (persistenceService == null) return;
        
        List<TorrentPersistenceService.SeedingTorrentState> states = 
            persistenceService.loadSeedingTorrents();
        
        for (var state : states) {
            try {
                // Check if data file still exists
                File dataFile = new File(state.dataFilePath);
                if (!dataFile.exists()) {
                    System.out.println("Skipping seeding torrent " + state.infoHashHex + 
                        ": data file not found at " + state.dataFilePath);
                    continue;
                }
                
                // Check if torrent file exists
                String torrentPath = persistenceService.getTorrentFilePath(state.infoHashHex);
                if (torrentPath == null) {
                    System.out.println("Skipping seeding torrent " + state.infoHashHex + 
                        ": torrent file not found");
                    continue;
                }
                
                // Load torrent and register
                bittorrent.torrent.Torrent torrent = bittorrent.service.BitTorrentService.loadTorrentStatic(torrentPath);
                TorrentInfo torrentInfo = torrent.info();
                
                activeTorrents.put(state.infoHashHex, torrentInfo);
                torrentFiles.put(state.infoHashHex, dataFile);
                
                System.out.println("Resumed seeding torrent: " + state.infoHashHex + 
                    " (" + state.fileName + ")");
                
                // Re-announce to tracker (will be handled by BitTorrentService)
            } catch (Exception e) {
                System.err.println("Failed to resume seeding torrent " + state.infoHashHex + 
                    ": " + e.getMessage());
                if (BitTorrentApplication.DEBUG) {
                    e.printStackTrace();
                }
            }
        }
    }

    public String getTorrentStatus(byte[] infoHash) {
        String infoHashHex = Main.HEX_FORMAT.formatHex(infoHash).toLowerCase();
        if (activeTorrents.containsKey(infoHashHex)) {
            // In a real implementation, we would check the actual bitfield of the file/torrent
            // For now, since we register it, we assume we are seeding it.
            // To be more precise, we'd need to access the shared BitSet for this torrent.
            return "Seeding: Yes | InfoHash: " + infoHashHex;
        }
        return "Seeding: No (Torrent not registered)";
    }
    
    /**
     * Check if a torrent is currently active (registered for seeding)
     */
    public boolean isTorrentActive(String infoHashHex) {
        return activeTorrents.containsKey(infoHashHex);
    }
    
    /**
     * Get all active torrents (info hashes)
     */
    public Set<String> getActiveTorrents() {
        return new HashSet<>(activeTorrents.keySet());
    }
    
    /**
     * Get file for a torrent
     */
    public File getTorrentFile(String infoHashHex) {
        return torrentFiles.get(infoHashHex);
    }
    
    /**
     * Check if the seeding file exists for a torrent
     */
    public boolean isSeedingFileExists(String infoHashHex) {
        File file = torrentFiles.get(infoHashHex);
        return file != null && file.exists();
    }
    
    /**
     * Validate all seeding torrents and remove those with missing files
     * @return List of info hashes that were removed due to missing files
     */
    public List<String> validateAndCleanupSeedingTorrents() {
        List<String> removed = new ArrayList<>();
        for (Map.Entry<String, File> entry : torrentFiles.entrySet()) {
            String infoHashHex = entry.getKey();
            File file = entry.getValue();
            if (file == null || !file.exists()) {
                // Remove torrent with missing file
                activeTorrents.remove(infoHashHex);
                torrentFiles.remove(infoHashHex);
                removed.add(infoHashHex);
                System.out.println("Removed seeding torrent " + infoHashHex + " due to missing file: " + 
                    (file != null ? file.getAbsolutePath() : "null"));
            }
        }
        if (!removed.isEmpty()) {
            saveSeedingTorrents();
        }
        return removed;
    }

    /**
     * Remove a torrent from the seeding registry.
     */
    public boolean unregisterTorrent(String infoHashHex) {
        if (infoHashHex == null) {
            return false;
        }
        String normalized = infoHashHex.toLowerCase();
        TorrentInfo removedInfo = activeTorrents.remove(normalized);
        File removedFile = torrentFiles.remove(normalized);
        if (removedInfo != null || removedFile != null) {
            System.out.println("Unregistered torrent: " + normalized);
            saveSeedingTorrents();
            return true;
        }
        return false;
    }

    private void acceptLoop() {
        while (running && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                executorService.submit(() -> handleConnection(clientSocket));
            } catch (IOException e) {
                if (running) {
                    System.err.println("Error accepting connection: " + e.getMessage());
                }
            }
        }
    }

    private void handleConnection(Socket socket) {
        try {
            System.out.println("Accepted connection from " + socket.getRemoteSocketAddress());
            
            DataInputStream inputStream = new DataInputStream(socket.getInputStream());
            DataOutputStream outputStream = new DataOutputStream(socket.getOutputStream());

            // 1. Read Handshake
            // Protocol length
            int length = inputStream.readByte();
            if (length != 19) {
                System.err.println("Invalid protocol length: " + length);
                socket.close();
                return;
            }

            // Protocol string
            byte[] protocolBytes = inputStream.readNBytes(19);
            if (!Arrays.equals(protocolBytes, PROTOCOL_BYTES)) {
                System.err.println("Invalid protocol string");
                socket.close();
                return;
            }

            // Reserved bytes
            byte[] reserved = inputStream.readNBytes(8);
            boolean supportExtensions = (reserved[5] & 0x10) != 0;

            // Info Hash
            byte[] infoHash = inputStream.readNBytes(20);
            String infoHashHex = Main.HEX_FORMAT.formatHex(infoHash);

            // Check if we are serving this torrent
            TorrentInfo torrentInfo = activeTorrents.get(infoHashHex);
            if (torrentInfo == null) {
                System.err.println("Unknown info hash: " + infoHashHex);
                socket.close();
                return;
            }

            // Peer ID
            byte[] peerId = inputStream.readNBytes(20);

            // 2. Send Handshake Response
            outputStream.write(19);
            outputStream.write(PROTOCOL_BYTES);
            outputStream.write(PADDING_EXTENSIONS); // Advertise extension protocol support (bit 5 = 0x10)
            outputStream.write(infoHash);
            outputStream.write(config.getPeerId().getBytes(StandardCharsets.US_ASCII)); // Our Peer ID

            // 3. Create Peer instance to handle the connection
            File file = torrentFiles.get(infoHashHex);
            Peer peer = new Peer(peerId, socket, supportExtensions, torrentInfo, file);
            
            // The Peer constructor starts the reader thread and registers with PeerConnectionManager
            System.out.println("Handshake successful with " + socket.getRemoteSocketAddress());
            
            // Register active peer with SwarmManager
            SwarmManager.getInstance().registerActivePeer(infoHashHex, 
                (java.net.InetSocketAddress) socket.getRemoteSocketAddress());
            
            // Initialize bitfield from file (checks which pieces are actually available)
            // This works for both complete files (seeding) and incomplete files (downloading)
            peer.initializeBitfieldFromFile();
            peer.sendOurBitfield();
            
            // If extensions are supported, send extension handshake response
            if (supportExtensions) {
                try {
                    // Send extension handshake to negotiate PEX and metadata extensions
                    final Map<String, Integer> localExtensions = Map.of(
                        "ut_metadata", 42,
                        "ut_pex", 43
                    );
                    peer.send(
                        new bittorrent.peer.protocol.Message.Extension(
                            (byte) 0,
                            new bittorrent.peer.protocol.MetadataMessage.Handshake(localExtensions)
                        ),
                        null
                    );
                    
                } catch (IOException e) {
                    if (BitTorrentApplication.DEBUG) {
                        System.err.printf("PeerServer: failed to send extension handshake: %s%n", e.getMessage());
                    }
                }
            }
            
            // Send initial PEX update after connection is established
            // Wait a moment for extension negotiation to complete
            new Thread(() -> {
                try {
                    Thread.sleep(2000); // Wait for extension handshake to complete
                    // Try to send PEX update (will be skipped if extension not negotiated)
                    peer.sendPexUpdate();
                } catch (Exception e) {
                    if (BitTorrentApplication.DEBUG) {
                        System.err.printf("PeerServer: failed to send initial PEX update: %s%n", e.getMessage());
                    }
                }
            }).start();
            
        } catch (IOException e) {
            System.err.println("Error handling connection: " + e.getMessage());
            try {
                socket.close();
            } catch (IOException ex) {
                // ignore
            }
        }
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
            executorService.shutdownNow();
        } catch (IOException e) {
            // ignore
        }
    }
}
