package bittorrent.tracker;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import bittorrent.BitTorrentApplication; // Changed from bittorrent.Main
import bittorrent.util.NetworkUtils;
import lombok.SneakyThrows;

public record AnnounceResponse(
	long interval,
	List<InetSocketAddress> peers
) {

	@SneakyThrows
	public static AnnounceResponse of(Map<String, Object> root, short selfPort) {
		if (BitTorrentApplication.DEBUG) { // Using constant from new application class
			System.err.println("AnnounceResponse: %s".formatted(root));
		}

		var interval = (Long) root.get("interval");
		if (interval == null) {
			interval = (long) root.get("mininterval");
		}

		final var peersList = new ArrayList<InetSocketAddress>();
		final var peersValue = root.get("peers");

		if (peersValue instanceof String compactPeers) {
			peersList.addAll(NetworkUtils.parseV4SocketAddresses(compactPeers));
		} else if (peersValue instanceof List<?> dictionaryPeers) {
			for (Object peerObject : dictionaryPeers) {
				if (peerObject instanceof Map<?, ?> peerMap) {
					final var ip = (String) peerMap.get("ip");
					final var port = ((Number) peerMap.get("port")).intValue();
					try {
						peersList.add(new InetSocketAddress(InetAddress.getByName(ip), port));
					} catch (UnknownHostException e) {
						// Ignore invalid peers
						System.err.println("Invalid peer address: " + ip + ":" + port);
					}
				}
			}
		}

		final var peers6Value = root.get("peers6");
		if (peers6Value instanceof String compactPeers6) {
			peersList.addAll(NetworkUtils.parseV6SocketAddresses(compactPeers6));
		}

		// peersList.removeIf((x) -> x.getPort() == selfPort);
		// peersList.removeIf((x) -> x.getAddress() instanceof Inet4Address);
		System.out.println(peersList);

		return new AnnounceResponse(interval, peersList);
	}

}