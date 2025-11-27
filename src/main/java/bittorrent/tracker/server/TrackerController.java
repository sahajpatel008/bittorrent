package bittorrent.tracker.server;

import bittorrent.Main;
import bittorrent.bencode.BencodeSerializer;
import bittorrent.tracker.model.PeerAddress;
import bittorrent.util.NetworkUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
public class TrackerController {

    private final TrackerService trackerService;
    private final BencodeSerializer bencodeSerializer = new BencodeSerializer();

    public TrackerController(TrackerService trackerService) {
        this.trackerService = trackerService;
    }

    @GetMapping(value = "/announce", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<byte[]> announce(
            @RequestParam("info_hash") String infoHashEncoded,
            @RequestParam("peer_id") String peerId,
            @RequestParam("port") int port,
			@RequestParam(value = "uploaded", defaultValue = "0") long uploaded,
			@RequestParam(value = "downloaded", defaultValue = "0") long downloaded,
			@RequestParam(value = "left", defaultValue = "-1") long left,
            @RequestParam(value = "compact", defaultValue = "0") int compact,
            HttpServletRequest request
    ) throws IOException {

        // 1. Decode Info Hash
        // Spring might have already decoded it partially or fully, but info_hash is raw bytes.
        // It's tricky with standard @RequestParam because info_hash can contain non-printable chars.
        // A safer way often involves manually parsing query string if standard decoding fails, 
        // but for now let's assume standard URL decoding works or we get the raw string.
        // However, the standard TrackerClient sends it url-encoded. 
        // We need to ensure we get the bytes back correctly.
        // A robust way is to re-encode to bytes if it was treated as ISO-8859-1.
        
        byte[] infoHashBytes = infoHashEncoded.getBytes(StandardCharsets.ISO_8859_1);
        String infoHashHex = Main.HEX_FORMAT.formatHex(infoHashBytes);

        // 2. Get Peer IP
        String ip = request.getRemoteAddr();

		// Simple debug log so we can see completion in tracker logs
		System.out.printf("Tracker announce: infoHash=%s ip=%s:%d left=%d downloaded=%d uploaded=%d%n",
				infoHashHex, ip, port, left, downloaded, uploaded);

        // 3. Register Peer
        trackerService.announce(infoHashHex, ip, port, peerId);

        // 4. Get Peers
        List<PeerAddress> peers = trackerService.getPeers(infoHashHex);

        // 5. Build Response
        Map<String, Object> response = new HashMap<>();
        response.put("interval", 1800L);
        response.put("min interval", 300L);

        if (compact == 1) {
             response.put("peers", serializeCompactPeers(peers));
        } else {
             response.put("peers", serializeDictionaryPeers(peers));
        }

        return ResponseEntity.ok(bencodeSerializer.writeAsBytes(response));
    }

    private byte[] serializeCompactPeers(List<PeerAddress> peers) {
        // 6 bytes per peer: 4 for IP, 2 for Port
        // Filter for IPv4 only for compact response
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        
        for (PeerAddress peer : peers) {
            try {
                InetAddress inetAddr = InetAddress.getByName(peer.ip());
                byte[] ipBytes = inetAddr.getAddress();
                if (ipBytes.length == 4) {
                    bos.write(ipBytes);
                    bos.write((peer.port() >> 8) & 0xFF);
                    bos.write(peer.port() & 0xFF);
                }
            } catch (Exception e) {
                // Ignore invalid IPs
            }
        }
        return bos.toByteArray();
    }

    private List<Map<String, Object>> serializeDictionaryPeers(List<PeerAddress> peers) {
        return peers.stream().map(peer -> {
            Map<String, Object> map = new HashMap<>();
            map.put("peer id", peer.peerId());
            map.put("ip", peer.ip());
            map.put("port", peer.port());
            return map;
        }).toList();
    }
}

