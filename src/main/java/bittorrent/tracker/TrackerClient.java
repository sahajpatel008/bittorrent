package bittorrent.tracker;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

import bittorrent.bencode.BencodeDeserializer;
import bittorrent.util.DigestUtils;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import java.util.concurrent.TimeUnit;

public class TrackerClient {

	private static final long UDP_PROTOCOL_ID = 0x41727101980L;

	/**
	 * BitTorrent tracker event types
	 */
	public enum Event {
		NONE(0, ""),      // Regular update (no event)
		STARTED(1, "started"),    // Download/upload started
		COMPLETED(2, "completed"), // Download completed (now seeding)
		STOPPED(3, "stopped");    // Download/upload stopped

		private final int udpValue;
		private final String httpValue;

		Event(int udpValue, String httpValue) {
			this.udpValue = udpValue;
			this.httpValue = httpValue;
		}

		public int getUdpValue() {
			return udpValue;
		}

		public String getHttpValue() {
			return httpValue;
		}
	}

	// Configure OkHttpClient with short timeouts to prevent blocking when tracker is unavailable
	// This ensures peer server runs independently of tracker availability
	private final OkHttpClient client = new OkHttpClient.Builder()
		.connectTimeout(2, TimeUnit.SECONDS)      // Connection timeout: 2 seconds
		.readTimeout(3, TimeUnit.SECONDS)        // Read timeout: 3 seconds
		.writeTimeout(2, TimeUnit.SECONDS)       // Write timeout: 2 seconds
		.build();
	private final String peerId;
	private final short listenPort;

	public TrackerClient() {
		this("00112233445566778899", (short) 6881);
	}

	public TrackerClient(String peerId, int listenPort) {
		this.peerId = peerId;
		this.listenPort = (short) listenPort;
	}

	public AnnounceResponse announce(Announceable announceable) throws IOException {
		return announce(announceable, listenPort, announceable.getInfoLength(), Event.NONE);
	}

	@SuppressWarnings("unchecked")
	public AnnounceResponse announce(Announceable announceable, int port) throws IOException {
		return announce(announceable, port, announceable.getInfoLength(), Event.NONE);
	}

	/**
	 * Announces with an explicit 'left' value so the tracker can know how much of
	 * the file this peer still needs (0 means completed / seeding).
	 */
	@SuppressWarnings("unchecked")
	public AnnounceResponse announce(Announceable announceable, int port, long left) throws IOException {
		return announce(announceable, port, left, Event.NONE);
	}

	/**
	 * Announces with an explicit 'left' value and event type.
	 * @param announceable The torrent to announce
	 * @param port The port this peer is listening on
	 * @param left Bytes remaining to download (0 means completed/seeding)
	 * @param event The event type (STARTED, COMPLETED, STOPPED, or NONE for regular updates)
	 */
	@SuppressWarnings("unchecked")
	public AnnounceResponse announce(Announceable announceable, int port, long left, Event event) throws IOException {
		String trackerUrl = announceable.getTrackerUrl();
		
		// Convert localhost to 127.0.0.1 to force IPv4 (avoid IPv6 resolution issues)
		if (trackerUrl != null && trackerUrl.contains("localhost")) {
			trackerUrl = trackerUrl.replace("localhost", "127.0.0.1");
		}
		
		System.out.println("Attempting to pass tracker url : " + trackerUrl);

		final var uri = URI.create(trackerUrl);
		final var scheme = uri.getScheme();

		if ("udp".equalsIgnoreCase(scheme)) {
			return announceUdp(announceable, uri, event);
		}

		if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
			throw new IllegalStateException("Unsupported tracker scheme: " + scheme);
		}

		var urlBuilder = Objects.requireNonNull(HttpUrl.parse(trackerUrl), "Invalid tracker URL")
			.newBuilder()
			.addEncodedQueryParameter("info_hash", DigestUtils.urlEncode(announceable.getInfoHash()))
			.addQueryParameter("peer_id", peerId)
			.addQueryParameter("port", String.valueOf(port))
			.addQueryParameter("uploaded", "0")
			.addQueryParameter("downloaded", String.valueOf(announceable.getInfoLength() - left))
			.addQueryParameter("left", String.valueOf(left))
			.addQueryParameter("compact", "1");
		
		// Add event parameter if not NONE
		if (event != Event.NONE && !event.getHttpValue().isEmpty()) {
			urlBuilder.addQueryParameter("event", event.getHttpValue());
		}
		
		final var request = new Request.Builder()
			.get()
			.url(urlBuilder.build())
			.build();

		try (
			final var response = client.newCall(request).execute();
			final var responseBody = response.body();
		) {
			if (!response.isSuccessful()) {
				throw new IllegalStateException(responseBody.string());
			}

			try (final var inputStream = responseBody.byteStream()) {
				final var deserializer = new BencodeDeserializer(inputStream);
				final var root = deserializer.parse();

				return AnnounceResponse.of((Map<String, Object>) root, listenPort);
			}
		}
	}

	private AnnounceResponse announceUdp(Announceable announceable, URI uri, Event event) throws IOException {
		String host = uri.getHost();
		// Convert localhost to 127.0.0.1 to force IPv4 (avoid IPv6 resolution issues)
		if (host != null && ("localhost".equalsIgnoreCase(host) || "::1".equals(host) || "0:0:0:0:0:0:0:1".equals(host))) {
			host = "127.0.0.1";
		}
		final var port = uri.getPort() == -1 ? 80 : uri.getPort();
		final var address = new InetSocketAddress(host, port);

		try (DatagramSocket socket = new DatagramSocket()) {
			socket.connect(address);
			socket.setSoTimeout(5000);

			final var connectionId = performUdpHandshake(socket);
			return performUdpAnnounce(socket, connectionId, announceable, event);
		}
	}

	private long performUdpHandshake(DatagramSocket socket) throws IOException {
		final var transactionId = ThreadLocalRandom.current().nextInt();

		final var requestBuffer = ByteBuffer.allocate(16);
		requestBuffer.putLong(UDP_PROTOCOL_ID);
		requestBuffer.putInt(0); // action = connect
		requestBuffer.putInt(transactionId);
		final var request = requestBuffer.array();
		socket.send(new DatagramPacket(request, request.length));

		final var responseBytes = new byte[16];
		final var responsePacket = new DatagramPacket(responseBytes, responseBytes.length);
		socket.receive(responsePacket);

		final var response = ByteBuffer.wrap(responseBytes);
		final var action = response.getInt();
		final var receivedTransactionId = response.getInt();
		if (action != 0 || receivedTransactionId != transactionId) {
			throw new IllegalStateException("Invalid UDP tracker handshake response");
		}

		return response.getLong();
	}

	private AnnounceResponse performUdpAnnounce(DatagramSocket socket, long connectionId, Announceable announceable, Event event) throws IOException {
		final var transactionId = ThreadLocalRandom.current().nextInt();
		final var infoHash = announceable.getInfoHash();
		final var peerIdBytes = peerId.getBytes(StandardCharsets.US_ASCII);

		final var announceBuffer = ByteBuffer.allocate(98);
		announceBuffer.putLong(connectionId);
		announceBuffer.putInt(1); // action = announce
		announceBuffer.putInt(transactionId);
		announceBuffer.put(infoHash);
		announceBuffer.put(peerIdBytes);
		announceBuffer.putLong(0); // downloaded
		announceBuffer.putLong(announceable.getInfoLength());
		announceBuffer.putLong(0); // uploaded
		announceBuffer.putInt(event.getUdpValue()); // event: 0=none, 1=started, 2=completed, 3=stopped
		announceBuffer.putInt(0); // IP address (default)
		announceBuffer.putInt(ThreadLocalRandom.current().nextInt()); // key
		announceBuffer.putInt(-1); // num want
		announceBuffer.putShort(listenPort);
		final var announceBytes = announceBuffer.array();
		socket.send(new DatagramPacket(announceBytes, announceBytes.length));

		final var responseBytes = new byte[4096];
		final var responsePacket = new DatagramPacket(responseBytes, responseBytes.length);
		socket.receive(responsePacket);

		final var response = ByteBuffer.wrap(responseBytes, 0, responsePacket.getLength());
		final var action = response.getInt();
		final var receivedTransactionId = response.getInt();
		if (action != 1 || receivedTransactionId != transactionId) {
			throw new IllegalStateException("Invalid UDP tracker announce response");
		}

		final var interval = Integer.toUnsignedLong(response.getInt());
		response.getInt(); // leechers (ignored)
		response.getInt(); // seeders (ignored)

		final var peers = new ArrayList<InetSocketAddress>();
		while (response.remaining() >= 6) {
			final var ipBytes = new byte[4];
			response.get(ipBytes);
			final var port = Short.toUnsignedInt(response.getShort());
			peers.add(new InetSocketAddress(InetAddress.getByAddress(ipBytes), port));
		}

		return new AnnounceResponse(interval, peers);
	}

}
