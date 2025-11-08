package bittorrent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.google.gson.Gson;

import bittorrent.bencode.BencodeDeserializer;
import bittorrent.magnet.Magnet;
import bittorrent.peer.Peer;
import bittorrent.torrent.Torrent;
import bittorrent.torrent.TorrentInfo;
import bittorrent.tracker.TrackerClient;
import bittorrent.tracker.Announceable;
import okhttp3.OkHttpClient;

// NOTE: Original Main.java code logic has been moved and adapted into this @Service class
// In a real Spring Boot app, the main entry point is BitTorrentApplication.java

// Renaming bittorrent.Main.java to bittorrent.service.BitTorrentService.java
// for better Spring component structure.
// I've put the implementation in bittorrent/service/BitTorrentService.java
// and the main class in bittorrent/BitTorrentApplication.java.

// Since the original file was Main.java, I'll update it to be a simple placeholder
// or, better yet, just leave the implementation in the service class and guide the user to delete this file.

// However, since I must provide code for the files referenced, I will rename the original Main.java content.

public class Main {
	public static final boolean DEBUG = BitTorrentApplication.DEBUG;

	public static final HexFormat HEX_FORMAT = BitTorrentApplication.HEX_FORMAT;
	public static final OkHttpClient CLIENT = new OkHttpClient();

	public static void main(String[] args) throws Exception {
		// This file is now a placeholder. All logic is in BitTorrentApplication and BitTorrentService.
		System.out.println("Spring Boot application handles execution.");
		// Removed all original switch-case logic.
		BitTorrentApplication.main(args);
	}

	// Retained original utility methods here for dependency resolution, 
	// but the logic is primarily executed via the service layer now.

	@SuppressWarnings("unchecked")
	public static Torrent load(String path) throws IOException {
		final var content = Files.readAllBytes(Paths.get(path));
		final var decoded = new BencodeDeserializer(content).parse();

		return Torrent.of((Map<String, Object>) decoded);
	}

	public static void info(String trackerUrl, TorrentInfo info) throws IOException {
		System.out.println("Tracker URL: %s".formatted(trackerUrl));
		System.out.println("Length: %d".formatted(info.length()));
		System.out.println("Info Hash: %s".formatted(HEX_FORMAT.formatHex(info.hash())));
		System.out.println("Piece Length: %d".formatted(info.pieceLength()));

		System.out.println("Piece Hashes:");
		for (final var hash : info.pieces()) {
			System.out.println(HEX_FORMAT.formatHex(hash));
		}
	}
}