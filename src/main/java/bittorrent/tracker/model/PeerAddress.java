package bittorrent.tracker.model;

import java.net.InetAddress;
import java.net.UnknownHostException;

public record PeerAddress(String ip, int port, long lastSeen) {
    public PeerAddress(String ip, int port) {
        this(ip, port, System.currentTimeMillis());
    }
}

