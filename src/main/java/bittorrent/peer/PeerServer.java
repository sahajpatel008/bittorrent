package bittorrent.peer;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.stereotype.Component;

import bittorrent.BitTorrentApplication;
import bittorrent.Main;
import bittorrent.config.BitTorrentConfig;
import bittorrent.torrent.TorrentInfo;

@Component
public class PeerServer {

    private static final byte[] PROTOCOL_BYTES = "BitTorrent protocol".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PADDING_8 = new byte[8];

    private final BitTorrentConfig config;
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final Map<String, TorrentInfo> activeTorrents = new ConcurrentHashMap<>();
    private final Map<String, File> torrentFiles = new ConcurrentHashMap<>();
    
    private ServerSocket serverSocket;
    private boolean running = false;

    public PeerServer(BitTorrentConfig config) {
        this.config = config;
    }

    public void start() {
        if (running) {
            return;
        }
        
        try {
            serverSocket = new ServerSocket(config.getListenPort());
            running = true;
            System.out.println("PeerServer listening on port " + config.getListenPort());
            
            Thread acceptThread = new Thread(this::acceptLoop);
            acceptThread.setName("PeerServer-Accept");
            acceptThread.start();
            
        } catch (IOException e) {
            System.err.println("Failed to start PeerServer: " + e.getMessage());
        }
    }

    public void registerTorrent(TorrentInfo torrentInfo, File file) {
        String infoHashHex = Main.HEX_FORMAT.formatHex(torrentInfo.hash());
        activeTorrents.put(infoHashHex, torrentInfo);
        torrentFiles.put(infoHashHex, file);
        System.out.println("Registered torrent for seeding: " + infoHashHex);
    }

    public String getTorrentStatus(byte[] infoHash) {
        String infoHashHex = Main.HEX_FORMAT.formatHex(infoHash);
        if (activeTorrents.containsKey(infoHashHex)) {
            // In a real implementation, we would check the actual bitfield of the file/torrent
            // For now, since we register it, we assume we are seeding it.
            // To be more precise, we'd need to access the shared BitSet for this torrent.
            return "Seeding: Yes | InfoHash: " + infoHashHex;
        }
        return "Seeding: No (Torrent not registered)";
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
            outputStream.write(PADDING_8); // We can support extensions later if needed
            outputStream.write(infoHash);
            outputStream.write(config.getPeerId().getBytes(StandardCharsets.US_ASCII)); // Our Peer ID

            // 3. Create Peer instance to handle the connection
            File file = torrentFiles.get(infoHashHex);
            Peer peer = new Peer(peerId, socket, supportExtensions, torrentInfo, file);
            
            // The Peer constructor starts the reader thread, so it's now active.
            // We should probably keep track of this peer somewhere, but for now let it run.
            System.out.println("Handshake successful with " + socket.getRemoteSocketAddress());
            
            // Send our bitfield immediately
            peer.sendOurBitfield();
            
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
