package bittorrent.util;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import bittorrent.bencode.BencodeSerializer;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;

@UtilityClass
public class DigestUtils {

    @SneakyThrows
    public static byte[] sha1(byte[] array) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(array);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    @SneakyThrows
    public static byte[] shaInfo(final Object infoRoot) {
        final var infoOutputStream = new ByteArrayOutputStream();
        try {
            new BencodeSerializer().write(infoRoot, infoOutputStream);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }

        return sha1(infoOutputStream.toByteArray());
    }

    @SneakyThrows
    public static String urlEncode(byte[] array) {
        try {
            return URLEncoder.encode(new String(array, StandardCharsets.ISO_8859_1), StandardCharsets.ISO_8859_1.name());
        } catch (java.io.UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

}
