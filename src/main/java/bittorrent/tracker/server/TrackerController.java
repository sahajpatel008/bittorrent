package bittorrent.tracker.server;

import bittorrent.Main;
import bittorrent.bencode.BencodeSerializer;
import bittorrent.tracker.model.PeerAddress;
import bittorrent.util.NetworkUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
            @RequestParam(value = "info_hash", required = false) String infoHashEncoded,
            @RequestParam("peer_id") String peerId,
            @RequestParam("port") int port,
			@RequestParam(value = "uploaded", defaultValue = "0") long uploaded,
			@RequestParam(value = "downloaded", defaultValue = "0") long downloaded,
			@RequestParam(value = "left", defaultValue = "-1") long left,
            @RequestParam(value = "compact", defaultValue = "0") int compact,
            HttpServletRequest request
    ) throws IOException {

        // 1. Decode Info Hash - manually extract from query string to preserve binary bytes
        // Spring's @RequestParam uses UTF-8 decoding which corrupts bytes > 127
        // We need to manually extract and decode using ISO-8859-1
        String infoHashHex;
        String queryString = request.getQueryString();
        if (queryString != null && queryString.contains("info_hash=")) {
            // Extract the raw info_hash parameter value
            int start = queryString.indexOf("info_hash=") + 10;
            int end = queryString.indexOf("&", start);
            if (end == -1) end = queryString.length();
            String rawInfoHash = queryString.substring(start, end);
            
            // URL-decode using ISO-8859-1 to preserve binary bytes
            String decoded = URLDecoder.decode(rawInfoHash, StandardCharsets.ISO_8859_1);
            byte[] infoHashBytes = decoded.getBytes(StandardCharsets.ISO_8859_1);
            infoHashHex = Main.HEX_FORMAT.formatHex(infoHashBytes);
        } else {
            // Fallback to Spring-decoded value
            byte[] infoHashBytes = infoHashEncoded.getBytes(StandardCharsets.ISO_8859_1);
            infoHashHex = Main.HEX_FORMAT.formatHex(infoHashBytes);
        }

        // 2. Get Peer IP - convert IPv6 localhost to IPv4
        String ip = request.getRemoteAddr();
        // Convert IPv6 localhost (::1) to IPv4 localhost (127.0.0.1)
        if ("::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)) {
            ip = "127.0.0.1";
        }
        // Filter out any other IPv6 addresses - only accept IPv4
        try {
            InetAddress addr = InetAddress.getByName(ip);
            if (addr.getAddress().length != 4) {
                // Not IPv4, skip this peer
                System.err.println("Rejecting IPv6 peer address: " + ip);
                ip = null;
            }
        } catch (Exception e) {
            System.err.println("Invalid peer IP address: " + ip);
            ip = null;
        }

		// Simple debug log so we can see completion in tracker logs
		if (ip != null) {
		    System.out.printf("Tracker announce: infoHash=%s ip=%s:%d left=%d downloaded=%d uploaded=%d%n",
			    infoHashHex, ip, port, left, downloaded, uploaded);
		}

        // 3. Register Peer (only if valid IPv4)
        if (ip != null) {
            trackerService.announce(infoHashHex, ip, port, peerId);
        }

        // 4. Get Peers (filtered to IPv4 only)
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
        return peers.stream()
            .filter(peer -> {
                // Only include IPv4 addresses
                try {
                    InetAddress addr = InetAddress.getByName(peer.ip());
                    return addr.getAddress().length == 4;
                } catch (Exception e) {
                    return false;
                }
            })
            .map(peer -> {
                Map<String, Object> map = new HashMap<>();
                map.put("peer id", peer.peerId());
                map.put("ip", peer.ip());
                map.put("port", peer.port());
                return map;
            }).toList();
    }

    /**
     * Manually register a peer in the tracker (REST API for testing/bootstrap)
     * POST /api/tracker/swarms/{infoHash}/peers
     * Body: { "ip": "192.168.1.1", "port": 6881, "peerId": "optional-peer-id" }
     */
    @PostMapping("/api/tracker/swarms/{infoHash}/peers")
    public ResponseEntity<Map<String, Object>> registerPeer(
            @PathVariable String infoHash,
            @RequestBody Map<String, Object> peerData) {
        try {
            String ip = (String) peerData.get("ip");
            Number portNum = (Number) peerData.get("port");
            String peerId = (String) peerData.get("peerId");
            
            if (ip == null || portNum == null) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Missing required fields: ip and port"));
            }
            
            int port = portNum.intValue();
            if (port < 1 || port > 65535) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Invalid port number: " + port));
            }
            
            trackerService.registerPeerManually(infoHash, ip, port, peerId);
            
            Map<String, Object> response = new HashMap<>();
            response.put("infoHash", infoHash);
            response.put("peer", Map.of("ip", ip, "port", port, "peerId", peerId != null ? peerId : "manual-peer"));
            response.put("message", "Peer registered in tracker successfully");
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "Failed to register peer: " + e.getMessage()));
        }
    }

    /**
     * Get all peers for a swarm (REST API)
     * GET /api/tracker/swarms/{infoHash}/peers
     */
    @GetMapping("/api/tracker/swarms/{infoHash}/peers")
    public ResponseEntity<Map<String, Object>> getSwarmPeers(@PathVariable String infoHash) {
        List<PeerAddress> peers = trackerService.getPeers(infoHash);
        
        List<Map<String, Object>> peerList = peers.stream()
            .map(peer -> {
                Map<String, Object> map = new HashMap<>();
                map.put("ip", peer.ip());
                map.put("port", peer.port());
                map.put("peerId", peer.peerId());
                map.put("lastSeen", peer.lastSeen());
                return map;
            })
            .toList();
        
        Map<String, Object> response = new HashMap<>();
        response.put("infoHash", infoHash);
        response.put("peers", peerList);
        response.put("count", peerList.size());
        
        return ResponseEntity.ok(response);
    }
}

