package bittorrent.peer.serial.extension;

import java.util.HashMap;
import java.util.Map;

import bittorrent.peer.protocol.MetadataMessage;
import bittorrent.peer.protocol.PexMessage;
import lombok.experimental.UtilityClass;

/**
 * Simple registry for extension codecs keyed by extension name.
 *
 * This allows us to support multiple extension payload types (metadata,
 * PEX, etc.) without hard-coding them into the wire protocol layer.
 */
@UtilityClass
public class ExtensionRegistry {

    public interface Codec<T> {
        Map<String, ?> serialize(T message);

        Object deserialize(java.util.List<Object> objects);
    }

    private static final Map<String, Codec<?>> NAME_TO_CODEC = new HashMap<>();

    static {
        // Metadata extension (ut_metadata)
        register("ut_metadata", new Codec<MetadataMessage>() {
            @Override
            public Map<String, ?> serialize(MetadataMessage message) {
                return MetadataMessageSerial.serialize(message);
            }

            @Override
            public Object deserialize(java.util.List<Object> objects) {
                return MetadataMessageSerial.deserialize(objects);
            }
        });

        // PEX extension (ut_pex)
        register("ut_pex", new Codec<PexMessage>() {
            @Override
            public Map<String, ?> serialize(PexMessage message) {
                return PexMessageSerial.serialize(message);
            }

            @Override
            public Object deserialize(java.util.List<Object> objects) {
                return PexMessageSerial.deserialize(objects);
            }
        });
    }

    public static <T> void register(String name, Codec<T> codec) {
        NAME_TO_CODEC.put(name, codec);
    }

    @SuppressWarnings("unchecked")
    public static <T> Codec<T> getByName(String name) {
        return (Codec<T>) NAME_TO_CODEC.get(name);
    }

    @SuppressWarnings("unchecked")
    public static <T> Codec<T> getByPayload(Object payload) {
        if (payload instanceof MetadataMessage) {
            return (Codec<T>) NAME_TO_CODEC.get("ut_metadata");
        }
        if (payload instanceof PexMessage) {
            return (Codec<T>) NAME_TO_CODEC.get("ut_pex");
        }
        return null;
    }
}


