package bittorrent.tracker.server;

import bittorrent.tracker.model.PeerAddress;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TrackerService {

    private final StringRedisTemplate redisTemplate;

    public static final int INTERVAL_SECONDS = 900; // 15 minutes
    private static final Duration PEER_TTL = Duration.ofSeconds(INTERVAL_SECONDS + 60);

    public TrackerService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public List<PeerAddress> announce(String infoHash, String ip, int port) {
        String normalizedHash = infoHash.toLowerCase();
        String key = "torrent:" + normalizedHash + ":peers";
        String peerEntry = ip + ":" + port;

        long now = System.currentTimeMillis();

        // Use ZSET (Sorted Set) exclusively.
        // Score = Timestamp (allows us to remove old peers)
        // Value = "IP:Port"
        redisTemplate.opsForZSet().add(key, peerEntry, now);
        
        // Remove old peers (older than 15 mins)
        long minTimestamp = now - (INTERVAL_SECONDS * 1000);
        redisTemplate.opsForZSet().removeRangeByScore(key, 0, minTimestamp);
        
        // Set key expiration
        redisTemplate.expire(key, PEER_TTL);

        // Get all active peers
        Set<String> members = redisTemplate.opsForZSet().range(key, 0, -1);

        if (members == null) {
            return Collections.emptyList();
        }

        return members.stream()
                .filter(m -> !m.equals(peerEntry)) // Exclude self
                .map(this::parsePeerEntry)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private PeerAddress parsePeerEntry(String entry) {
        try {
            String[] parts = entry.split(":");
            if (parts.length != 2) return null;
            return new PeerAddress(parts[0], Integer.parseInt(parts[1]), 0);
        } catch (Exception e) {
            return null;
        }
    }
}
