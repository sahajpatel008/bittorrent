package bittorrent.tracker;

import java.io.IOException;
import java.util.Map;

import bittorrent.bencode.BencodeDeserializer;
import bittorrent.util.DigestUtils;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;

public class TrackerClient {

	public final OkHttpClient client = new OkHttpClient();

	public AnnounceResponse announce(Announceable announceable) throws IOException {
		return announce(announceable, 6881, announceable.getInfoLength());
	}

	@SuppressWarnings("unchecked")
	public AnnounceResponse announce(Announceable announceable, int port) throws IOException {
		return announce(announceable, port, announceable.getInfoLength());
	}

	/**
	 * Announces with an explicit 'left' value so the tracker can know how much of
	 * the file this peer still needs (0 means completed / seeding).
	 */
	@SuppressWarnings("unchecked")
	public AnnounceResponse announce(Announceable announceable, int port, long left) throws IOException {
		final short selfPort = (short) port;
		final var trackerUrl = announceable.getTrackerUrl();
		System.out.println("Attempting to pass tracker url : " + trackerUrl);
		final var request = new Request.Builder()
			.get()
			.url(
				HttpUrl.parse(trackerUrl)
					.newBuilder()
					.addEncodedQueryParameter("info_hash", DigestUtils.urlEncode(announceable.getInfoHash()))
					.addQueryParameter("peer_id", "00112233445566778899")
					.addQueryParameter("port", String.valueOf(selfPort))
					.addQueryParameter("uploaded", "0")
					.addQueryParameter("downloaded", String.valueOf(announceable.getInfoLength() - left))
					.addQueryParameter("left", String.valueOf(left))
					.addQueryParameter("compact", "1")
					.build()
			)
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

				return AnnounceResponse.of((Map<String, Object>) root, selfPort);
			}
		}
	}

}