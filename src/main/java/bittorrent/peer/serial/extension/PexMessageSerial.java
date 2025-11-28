package bittorrent.peer.serial.extension;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import bittorrent.peer.protocol.PexMessage;
import bittorrent.util.NetworkUtils;
import lombok.experimental.UtilityClass;

/**
 * Serializer / deserializer for simple PEX messages.
 *
 * Wire format (bencoded dictionary):
 * {
 *   "added":   <compact IPv4 string>,
 *   "dropped": <compact IPv4 string>
 * }
 */
@UtilityClass
public class PexMessageSerial {

    private static final String ADDED_KEY = "added";
    private static final String DROPPED_KEY = "dropped";

    public static Map<String, ?> serialize(PexMessage message) {
        if (message instanceof PexMessage.Pex pex) {
            String addedCompact = NetworkUtils.toV4CompactString(pex.added());
            String droppedCompact = NetworkUtils.toV4CompactString(pex.dropped());

            return Map.of(
                ADDED_KEY, addedCompact,
                DROPPED_KEY, droppedCompact
            );
        }
        throw new UnsupportedOperationException("Unknown PexMessage type: " + message);
    }

    @SuppressWarnings("unchecked")
    public static PexMessage deserialize(List<Object> objects) {
        if (objects == null || objects.isEmpty()) {
            return new PexMessage.Pex(Collections.emptyList(), Collections.emptyList());
        }

        final var content = (Map<String, Object>) objects.getFirst();

        List<InetSocketAddress> added = Collections.emptyList();
        List<InetSocketAddress> dropped = Collections.emptyList();

        Object addedRaw = content.get(ADDED_KEY);
        if (addedRaw instanceof String addedStr && !addedStr.isEmpty()) {
            added = NetworkUtils.parseV4SocketAddresses(addedStr);
        }

        Object droppedRaw = content.get(DROPPED_KEY);
        if (droppedRaw instanceof String droppedStr && !droppedStr.isEmpty()) {
            dropped = NetworkUtils.parseV4SocketAddresses(droppedStr);
        }

        return new PexMessage.Pex(added, dropped);
    }
}


