package bittorrent.tracker.model;

public record PeerAddress(
    String ip,
    int port,
    String peerId,
    long lastSeen
) {}



