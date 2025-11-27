package bittorrent.torrent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import bittorrent.util.DigestUtils;

public record TorrentInfo(
	byte[] hash,
	long length,
	String name,
	int pieceLength,
	List<byte[]> pieces
) {

	public static TorrentInfo of(Map<String, Object> root) {
		final var hash = DigestUtils.shaInfo(root);
		final long length;
		if (root.containsKey("length")) {
			// Single-file mode
			length = (long) root.get("length");
		} else {
			// Multi-file mode: sum lengths of all files
			@SuppressWarnings("unchecked")
			final var files = (List<Map<String, Object>>) root.get("files");
			length = files.stream()
				.mapToLong(file -> (long) file.get("length"))
				.sum();
		}
		final var name = (String) root.get("name");
		final var pieceLength = (int) (long) root.get("piece length");

		final var pieceHashes = ((String) root.get("pieces")).getBytes(StandardCharsets.ISO_8859_1);
		final var pieces = new ArrayList<byte[]>();
		for (var start = 0; start < pieceHashes.length; start += 20) {
			final var piece = Arrays.copyOfRange(pieceHashes, start, start + 20);
			pieces.add(piece);
		}

		return new TorrentInfo(hash, length, name, pieceLength, pieces);
	}

}