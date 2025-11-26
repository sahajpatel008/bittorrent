package bittorrent.tracker.server;

import bittorrent.bencode.BencodeSerializer;
import bittorrent.tracker.model.PeerAddress;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@RestController
public class TrackerController {

    private final TrackerService trackerService;
    private final BencodeSerializer bencodeSerializer;
    private static final HexFormat HEX_FORMAT = HexFormat.of();

    public TrackerController(TrackerService trackerService) {
        this.trackerService = trackerService;
        this.bencodeSerializer = new BencodeSerializer();
    }

    @GetMapping("/announce")
    public ResponseEntity<byte[]> announce(
            @RequestParam("info_hash") String infoHashStr,
            @RequestParam("peer_id") String peerId,
            @RequestParam("port") int port,
            @RequestParam(value = "compact", defaultValue = "0") int compact,
            HttpServletRequest request
    ) throws IOException {

        // info_hash is expected to be URL-encoded. Spring decodes it.
        // We re-encode to bytes using ISO-8859-1 which preserves the byte values if they were 1-1 mapped.
        byte[] infoHashBytes = infoHashStr.getBytes(StandardCharsets.ISO_8859_1);
        String infoHashHex = HEX_FORMAT.formatHex(infoHashBytes);

        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty()) {
            ip = request.getRemoteAddr();
        } else {
             // X-Forwarded-For can be "client, proxy1, proxy2". We want the first one.
             ip = ip.split(",")[0].trim();
        }

        // In local dev, 0:0:0:0:0:0:0:1 is common. Treat as 127.0.0.1 for compact compatibility
        if ("0:0:0:0:0:0:0:1".equals(ip)) {
            ip = "127.0.0.1";
        }

        List<PeerAddress> peers = trackerService.announce(infoHashHex, ip, port);

        Map<String, Object> response = new HashMap<>();
        response.put("interval", TrackerService.INTERVAL_SECONDS);
        response.put("min interval", TrackerService.INTERVAL_SECONDS / 2);

        if (compact == 1) {
            response.put("peers", createCompactPeers(peers));
        } else {
            // Non-compact response (List of Dictionaries)
            // Not strictly required by plan but good for completeness/debugging
            // We'll stick to what the plan implies or just empty list if not compact for now to save time,
            // but the plan said "Response: ... peers: (string) binary compact".
            // So we'll prioritize compact.
            response.put("peers", createCompactPeers(peers));
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bencodeSerializer.write(response, out);

        return ResponseEntity.ok()
                .header("Content-Type", "text/plain")
                .body(out.toByteArray());
    }

    private String createCompactPeers(List<PeerAddress> peers) {
        ByteBuffer buffer = ByteBuffer.allocate(peers.size() * 6);
        for (PeerAddress peer : peers) {
            try {
                InetAddress addr = InetAddress.getByName(peer.ip());
                byte[] ipBytes = addr.getAddress();
                if (ipBytes.length != 4) {
                    continue; // Skip IPv6 or invalid for compact response
                }
                buffer.put(ipBytes);
                buffer.putShort((short) peer.port());
            } catch (Exception e) {
                // Ignore invalid IPs
            }
        }
        // Flip to read mode
        buffer.flip();
        byte[] validBytes = new byte[buffer.remaining()];
        buffer.get(validBytes);

        return new String(validBytes, StandardCharsets.ISO_8859_1);
    }
}
