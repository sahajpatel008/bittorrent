package bittorrent.peer.serial;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Context passed to message serializers/deserializers to provide
 * per-connection extension metadata.
 *
 * For extension messages, we need to know which numeric extension ID
 * corresponds to which logical extension name (e.g., \"ut_metadata\",
 * \"ut_pex\"), so that we can route payloads to the appropriate codec.
 */
public class MessageSerialContext {

	private final Map<Byte, String> extensionIdToName = new HashMap<>();

	public void registerExtension(byte id, String name) {
		extensionIdToName.put(id, name);
	}

	public String getExtensionName(byte id) {
		return extensionIdToName.get(id);
	}

	public Map<Byte, String> getAllExtensions() {
		return Collections.unmodifiableMap(extensionIdToName);
	}
}
