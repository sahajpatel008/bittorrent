package bittorrent.config;

import java.nio.charset.StandardCharsets;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import lombok.Data;

@Configuration
@ConfigurationProperties(prefix = "bittorrent")
@Data
public class BitTorrentConfig {
    
    /**
     * Peer ID used to identify this client (20 bytes/characters)
     */
    private String peerId = "42112233445566778899";
    
    @PostConstruct
    public void validate() {
        byte[] peerIdBytes = peerId.getBytes(StandardCharsets.US_ASCII);
        if (peerIdBytes.length != 20) {
            throw new IllegalStateException(
                "bittorrent.peer-id must be exactly 20 ASCII characters. " +
                "Current length: " + peerIdBytes.length + " bytes (configured value: '" + peerId + "')"
            );
        }
    }
    
    /**
     * Port to listen on for incoming peer connections
     */
    private int listenPort = 6881;
    
    /**
     * Directory where downloaded files are stored
     */
    private String downloadDir = "./downloads";
    
    /**
     * Maximum number of simultaneous peer connections
     */
    private int maxConnections = 50;
    
    /**
     * Maximum upload rate in bytes per second (-1 for unlimited)
     */
    private long maxUploadRate = -1;
    
    /**
     * Maximum download rate in bytes per second (-1 for unlimited)
     */
    private long maxDownloadRate = -1;
    
    /**
     * Tracker connection timeout in milliseconds
     */
    private int trackerTimeout = 30000;
    
    /**
     * Default tracker re-announce interval in seconds
     */
    private int trackerInterval = 1800;
}
