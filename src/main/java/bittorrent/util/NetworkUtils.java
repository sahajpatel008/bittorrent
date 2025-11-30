package bittorrent.util;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;

@UtilityClass
public class NetworkUtils {

	public static List<InetSocketAddress> parseV4SocketAddresses(String input) {
		return parseSocketAddresses(32 / 8, input);
	}

	public static List<InetSocketAddress> parseV6SocketAddresses(String input) {
		return parseSocketAddresses(128 / 8, input);
	}

	@SneakyThrows
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
			} catch (java.net.UnknownHostException e) {
				throw new RuntimeException(e);
			}
		}

		return addresses;
	}

	/**
	 * Serializes a list of IPv4 socket addresses into the compact BitTorrent
	 * representation (6 bytes per peer: 4 for IP, 2 for port) and returns it as
	 * an ISO-8859-1 string.
	 */
	public static String toV4CompactString(List<InetSocketAddress> addresses) {
		if (addresses == null || addresses.isEmpty()) {
			return "";
		}

		final var bytes = new java.io.ByteArrayOutputStream();

		for (InetSocketAddress addr : addresses) {
			try {
				byte[] ipBytes = addr.getAddress().getAddress();
				// Only support IPv4 for compact representation
				if (ipBytes.length != 4) {
					continue;
				}

				bytes.write(ipBytes);
				int port = addr.getPort();
				bytes.write((port >> 8) & 0xFF);
				bytes.write(port & 0xFF);
			} catch (Exception e) {
				// Skip invalid addresses
			}
		}

		return new String(bytes.toByteArray(), StandardCharsets.ISO_8859_1);
	}

}