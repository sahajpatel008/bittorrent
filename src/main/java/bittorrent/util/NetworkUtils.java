package bittorrent.util;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import lombok.experimental.UtilityClass;

public class NetworkUtils {

    private NetworkUtils() {}

	public static List<InetSocketAddress> parseV4SocketAddresses(String input) {
		return parseSocketAddresses(32 / 8, input);
	}

	public static List<InetSocketAddress> parseV6SocketAddresses(String input) {
		return parseSocketAddresses(128 / 8, input);
	}

	private static List<InetSocketAddress> parseSocketAddresses(int length, String input) {
		if (input == null) {
			return Collections.emptyList();
		}

		final var addresses = new ArrayList<InetSocketAddress>();

		final var bytes = input.getBytes(StandardCharsets.ISO_8859_1);
		for (var start = 0; start < bytes.length; start += length + 2) {
			final var address = Arrays.copyOfRange(bytes, start, start + length);
			final var port = ((bytes[start + length] & 0xff) << 8) + (bytes[start + length + 1] & 0xff);

			try {
				final var peer = new InetSocketAddress(InetAddress.getByAddress(address), port);
				addresses.add(peer);
			} catch (UnknownHostException e) {
				// Should not happen if address length is correct (4 or 16)
                // If it does, we just skip this peer
			}
		}

		return addresses;
	}

}
