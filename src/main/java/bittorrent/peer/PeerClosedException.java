package bittorrent.peer;

import lombok.experimental.StandardException;

@SuppressWarnings("serial")
@StandardException
public class PeerClosedException extends RuntimeException {
    public PeerClosedException(Throwable cause) {
        super(cause);
    }
    public PeerClosedException(String message) {
        super(message);
    }
    public PeerClosedException() {
        super();
    }
}