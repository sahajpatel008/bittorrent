package bittorrent.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import bittorrent.bencode.BencodeSerializer;

public class DigestUtils {

    private DigestUtils() {}

	public static byte[] sha1(byte[] array) {
		try {
			return MessageDigest.getInstance("SHA-1").digest(array);
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
	}

	public static byte[] shaInfo(final Object infoRoot) {
		try {
			final var infoOutputStream = new ByteArrayOutputStream();
			new BencodeSerializer().write(infoRoot, infoOutputStream);
			return sha1(infoOutputStream.toByteArray());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public static String urlEncode(byte[] array) {
		try {
			return URLEncoder.encode(new String(array, StandardCharsets.ISO_8859_1), StandardCharsets.ISO_8859_1.name());
		} catch (UnsupportedEncodingException e) {
			throw new RuntimeException(e);
		}
	}

}
