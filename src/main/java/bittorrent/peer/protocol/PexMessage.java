package bittorrent.peer.protocol;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * Simple PEX (Peer Exchange) message model used for gossiping peer addresses.
 *
 * For now we model a single message type that contains peers that have been
 * added to or dropped from the local view of the swarm.
 */
public sealed interface PexMessage {

    /**
     * PEX payload with peers that have been added to or dropped from the swarm
     * from the sender's point of view.
     */
    public record Pex(
        List<InetSocketAddress> added,
        List<InetSocketAddress> dropped
    ) implements PexMessage {}
}


