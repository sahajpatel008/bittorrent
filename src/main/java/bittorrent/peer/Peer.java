package bittorrent.peer;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.io.RandomAccessFile;
import java.io.File;
import java.io.FileNotFoundException;

import bittorrent.BitTorrentApplication;
import bittorrent.Main;
import bittorrent.magnet.Magnet;
import bittorrent.peer.protocol.Message;
import bittorrent.peer.protocol.MetadataMessage;
import bittorrent.peer.protocol.PexMessage;
import bittorrent.peer.serial.MessageDescriptor;
import bittorrent.peer.serial.MessageDescriptors;
import bittorrent.peer.serial.MessageSerialContext;
import bittorrent.torrent.TorrentInfo;
import bittorrent.tracker.Announceable;
import bittorrent.util.DigestUtils;
import bittorrent.util.ExposedByteArrayOutputStream;
import lombok.Getter;

public class Peer implements AutoCloseable {

	private static final byte[] PROTOCOL_BYTES = "BitTorrent protocol".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] PADDING_8 = new byte[8];
	private static final byte[] PADDING_MAGNET_8 = { 0, 0, 0, 0, 0, 0x10, 0, 0 };

	// Per-connection extension context (IDs are negotiated via handshake)
	private final MessageSerialContext extensionContext = new MessageSerialContext();

	public byte[] getId() {
		return id;
	}
	private final byte[] id;
	private final Socket socket;
	private final boolean supportExtensions;

	private boolean bitfield;
	private boolean interested;
	private @Getter int metadataExtensionId = -1;
	private @Getter int pexExtensionId = -1;

	// Hex-encoded info hash for swarm bookkeeping (PEX, SwarmManager)
	private final String infoHashHex;

	private Deque<Message> receiveQueue;

	// New fields for the reader thread and upload logic
	private final TorrentInfo torrentInfo;
	private final File downloadedFile;
	private final Thread readerThread;
	private final BlockingQueue<Message.Piece> pieceQueue = new LinkedBlockingDeque<>();

	private volatile boolean peerInterested = false;
	private volatile boolean amChoking = true;

	// Client-side bitfield to track which pieces we have downloaded and verified
	private final BitSet clientBitfield;

	// PEX-related fields
	private final InetSocketAddress remoteAddress;
	private final Thread pexUpdateThread;
	private volatile boolean closed = false;
	private static final long PEX_UPDATE_INTERVAL_MS = 15_000; // 15 seconds 

	public Peer(byte[] id, Socket socket, boolean supportExtensions, TorrentInfo torrentInfo, File downloadedFile) {
		this.id = id;
		this.socket = socket;
		this.supportExtensions = supportExtensions;
		this.torrentInfo = torrentInfo; 
		this.downloadedFile = downloadedFile; 
		this.infoHashHex = Main.HEX_FORMAT.formatHex(torrentInfo.hash());

		// Store remote address for PEX
		this.remoteAddress = (InetSocketAddress) socket.getRemoteSocketAddress();

		this.receiveQueue = new LinkedList<>();

		// Initialize bitfield with the number of pieces in the torrent
		this.clientBitfield = new BitSet(torrentInfo.pieces().size());

		// Start the reader thread
		this.readerThread = new Thread(this::runReaderLoop);
		this.readerThread.setName("PeerReader-" + socket.getRemoteSocketAddress());
		this.readerThread.setDaemon(true);
		this.readerThread.start();

		// Start PEX update thread for periodic updates
		this.pexUpdateThread = new Thread(this::runPexUpdateLoop);
		this.pexUpdateThread.setName("PeerPex-" + socket.getRemoteSocketAddress());
		this.pexUpdateThread.setDaemon(true);
		this.pexUpdateThread.start();

		// Register with PeerConnectionManager
		PeerConnectionManager.getInstance().registerConnection(infoHashHex, this);
	}

	/**
	 * Marks all pieces as present in the local bitfield.
	 * Intended for seeder-side peers that already have the full file.
	 */
	public void markAllPiecesPresent() {
		clientBitfield.set(0, torrentInfo.pieces().size());
	}
	
	/**
	 * Mark a specific piece as available in the bitfield.
	 * Used when a piece becomes available during download.
	 */
	public void markPieceAvailable(int pieceIndex) {
		clientBitfield.set(pieceIndex);
	}
	
	/**
	 * Initialize bitfield by checking which pieces are actually available in the file.
	 * This allows serving pieces during an active download.
	 */
	public void initializeBitfieldFromFile() {
		if (downloadedFile == null || !downloadedFile.exists()) {
			return;
		}
		
		try (RandomAccessFile raf = new RandomAccessFile(downloadedFile, "r")) {
			long fileLength = raf.length();
			long expectedLength = torrentInfo.length();
			int pieceLength = torrentInfo.pieceLength();
			int pieceCount = torrentInfo.pieces().size();
			
			// Check each piece to see if it's available
			for (int pieceIndex = 0; pieceIndex < pieceCount; pieceIndex++) {
				long pieceStart = (long) pieceIndex * pieceLength;
				
				// Check if piece position is within file bounds
				if (pieceStart >= fileLength) {
					continue; // Piece not written yet
				}
				
				// Calculate actual piece size (last piece may be smaller)
				long actualPieceSize = (pieceIndex == pieceCount - 1) 
					? (expectedLength % pieceLength)
					: pieceLength;
				
				// Check if we can read the full piece
				if (pieceStart + actualPieceSize > fileLength) {
					continue; // Piece not fully written yet
				}
				
				// Try to read and verify the piece
				try {
					raf.seek(pieceStart);
					byte[] pieceData = new byte[(int) actualPieceSize];
					int bytesRead = raf.read(pieceData);
					
					if (bytesRead == actualPieceSize) {
						// Verify piece hash
						byte[] pieceHash = DigestUtils.sha1(pieceData);
						byte[] expectedHash = torrentInfo.pieces().get(pieceIndex);
						
						if (Arrays.equals(pieceHash, expectedHash)) {
							// Piece is valid, mark it as available
							clientBitfield.set(pieceIndex);
						}
					}
				} catch (IOException e) {
					// Piece not available or corrupted, skip it
					if (BitTorrentApplication.DEBUG) {
						System.err.println("Could not verify piece " + pieceIndex + ": " + e.getMessage());
					}
				}
			}
		} catch (IOException e) {
			if (BitTorrentApplication.DEBUG) {
				System.err.println("Could not initialize bitfield from file: " + e.getMessage());
			}
		}
	}

	private Message doReceive(MessageSerialContext context) throws IOException {
		final var dataInputStream = new DataInputStream(socket.getInputStream());

		final int length;
		try {
			length = dataInputStream.readInt();
		} catch (EOFException exception) {
			throw new PeerClosedException(exception);
		}

		final var typeId = length != 0 ? dataInputStream.readByte() : (byte) -1;

		final var descriptor = MessageDescriptors.getByTypeId(typeId);
		final var message = descriptor.deserialize(length - 1, dataInputStream, context);

		System.err.println("recv: typeId=%-2d length=%-6d message=%s".formatted(descriptor.typeId(), length, message));

		return message;
	}

	public Message receive(boolean lookAtQueue, MessageSerialContext context) throws IOException {
		if (lookAtQueue && !receiveQueue.isEmpty()) {
			final var message = receiveQueue.removeFirst();

			System.err.println("queue recv: message=%s".formatted(message));

			return message;
		}

		var message = doReceive(context);

		if (message instanceof Message.KeepAlive) {
			send(message, context);
			return receive(lookAtQueue, context);
		}

		return message;
	}

	public Message waitFor(Predicate<Message> predicate, MessageSerialContext context) throws IOException {
		// First check the queue
		var iterator = receiveQueue.iterator();
		while (iterator.hasNext()) {
			final var message = iterator.next();

			if (predicate.test(message)) {
				System.err.println("wait for: found: message=%s".formatted(message));

				iterator.remove();
				return message;
			}
		}

		// If not in queue, wait for reader loop to add it
		// Don't read directly from socket - the reader loop handles that
		while (true) {
			synchronized (receiveQueue) {
				// Check queue again (reader loop might have added messages)
				iterator = receiveQueue.iterator();
				while (iterator.hasNext()) {
					final var message = iterator.next();
					if (predicate.test(message)) {
						System.err.println("wait for: found: message=%s".formatted(message));
						iterator.remove();
						return message;
					}
				}
				
				// Wait for reader loop to add a message
				try {
					receiveQueue.wait(100); // Wait up to 100ms, then check again
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IOException("Interrupted while waiting for message", e);
				}
			}
		}
	}

	@SuppressWarnings("unchecked")
	public <T extends Message> T waitFor(Class<T> clazz, MessageSerialContext context) throws IOException {
		return (T) waitFor((message) -> clazz.equals(message.getClass()), context);
	}

	public void send(Message message) throws IOException {
		send(message, null);
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	public void send(Message message, MessageSerialContext context) throws IOException {
		final var dataOutputStream = new DataOutputStream(socket.getOutputStream());

		final MessageDescriptor descriptor = MessageDescriptors.getByClass(message.getClass());

		final var byteArrayOutputStream = new ExposedByteArrayOutputStream();
		final var length = descriptor.serialize(message, new DataOutputStream(byteArrayOutputStream), context);

		System.err.println("send: typeId=%-2d length=%-6d message=%s".formatted(descriptor.typeId(), length, message));

		dataOutputStream.writeInt(length);
		if (length == 0) {
			return;
		}

		dataOutputStream.writeByte(descriptor.typeId());
		dataOutputStream.write(byteArrayOutputStream.getBuffer(), 0, length - 1);
	}

	public void awaitBitfield() throws IOException, InterruptedException {
		// If we are the server (responder), we might have already sent our bitfield in the handshake logic
		// or we might want to send it here. The current logic sends it at the end of this method.
		
		if (bitfield) {
			return;
		}

		if (supportExtensions) {
			// Advertise the extensions we support. For now, we announce
			// ut_metadata and ut_pex with arbitrary local IDs.
			final Map<String, Integer> localExtensions = Map.of(
				"ut_metadata", 42,
				"ut_pex", 43
			);

			send(
				new Message.Extension(
					(byte) 0,
					new MetadataMessage.Handshake(localExtensions)
				),
				null
			);
			
			if (BitTorrentApplication.DEBUG) {
				System.err.printf("Peer[%s]: Sent extension handshake, waiting for response...%n", remoteAddress);
			}
			
			// Wait for extension handshake response
			// Check queue first, then wait if needed
			Message.Extension extension = null;
			
			// First check if extension is already in queue
			var queueIter = receiveQueue.iterator();
			while (queueIter.hasNext()) {
				var msg = queueIter.next();
				if (msg instanceof Message.Extension ext && ext.id() == 0) {
					extension = ext;
					queueIter.remove();
					if (BitTorrentApplication.DEBUG) {
						System.err.printf("Peer[%s]: Found extension handshake in queue%n", remoteAddress);
					}
					break;
				}
			}
			
			// If not in queue, wait for it
			if (extension == null) {
				if (BitTorrentApplication.DEBUG) {
					System.err.printf("Peer[%s]: Extension handshake not in queue, waiting...%n", remoteAddress);
				}
				extension = waitFor(Message.Extension.class, null);
				if (BitTorrentApplication.DEBUG) {
					System.err.printf("Peer[%s]: Received extension handshake response%n", remoteAddress);
				}
			}
			
			System.err.println("extension: %s".formatted(extension));

			// Deserialize handshake content
			@SuppressWarnings("unchecked")
			final var objects = (java.util.List<Object>) extension.content();
			final var metadata = bittorrent.peer.serial.extension.MetadataMessageSerial.deserialize(objects);
			if (metadata instanceof MetadataMessage.Handshake handshake) {
				var ids = handshake.extensionIds();
				if (ids.containsKey("ut_metadata")) {
					metadataExtensionId = ids.get("ut_metadata");
					extensionContext.registerExtension((byte) metadataExtensionId, "ut_metadata");
				}
				if (ids.containsKey("ut_pex")) {
					pexExtensionId = ids.get("ut_pex");
					extensionContext.registerExtension((byte) pexExtensionId, "ut_pex");
				}
			}
		}

		// Check if bitfield was already received (might have arrived before we got here)
		if (bitfield) {
			if (BitTorrentApplication.DEBUG) {
				System.err.printf("Peer[%s]: Bitfield already received, proceeding%n", remoteAddress);
			}
			return; // Bitfield already received, we're done
		}
		
		// Check receive queue for bitfield msg first
		var queueIter = receiveQueue.iterator();
		while (queueIter.hasNext()) {
			var msg = queueIter.next();
			if (msg instanceof Message.Bitfield bf) {
				handleMessage(bf); // This sets bitfield = true
				queueIter.remove();
				if (BitTorrentApplication.DEBUG) {
					System.err.printf("Peer[%s]: Found bitfield in queue, proceeding%n", remoteAddress);
				}
				return; // Found bitfield in queue, we're done
			}
		}
		
		// If still no bitfield, wait for it using waitFor (which properly blocks)
		if (BitTorrentApplication.DEBUG) {
			System.err.printf("Peer[%s]: Waiting for bitfield...%n", remoteAddress);
		}
		var bitfieldMsg = waitFor(Message.Bitfield.class, null);
		handleMessage(bitfieldMsg); // This sets bitfield = true

		// Send our bitfield to the peer to let them know what pieces we have
		sendOurBitfield();
		
		// Send initial PEX update after connection is established and extensions are negotiated
		// This helps peers discover each other immediately, not just after 30 seconds
		if (pexExtensionId >= 0) {
			try {
				// Wait a moment for the connection to stabilize
				Thread.sleep(1000);
				sendPexUpdate();
			} catch (Exception e) {
				if (BitTorrentApplication.DEBUG) {
					System.err.printf("Peer[%s]: failed to send initial PEX update: %s%n", remoteAddress, e.getMessage());
				}
			}
		}
	}

	/**
	 * Sends our bitfield to the peer, indicating which pieces we have.
	 * Only sends if we have at least one piece.
	 */
	public void sendOurBitfield() throws IOException {
		// Only send bitfield if we have at least one piece
		if (clientBitfield.isEmpty()) {
			return;
		}

		// Convert BitSet to byte array for the wire protocol
		// BitTorrent bitfield: each bit represents a piece (1 = have, 0 = don't have)
		int numPieces = torrentInfo.pieces().size();
		int numBytes = (numPieces + 7) / 8; // Round up to nearest byte
		byte[] bitfieldBytes = new byte[numBytes];

		for (int i = 0; i < numPieces; i++) {
			if (clientBitfield.get(i)) {
				int byteIndex = i / 8;
				int bitIndex = 7 - (i % 8); // MSB first
				bitfieldBytes[byteIndex] |= (1 << bitIndex);
			}
		}

		send(new Message.Bitfield(bitfieldBytes));
	}

	public byte[] downloadPiece(TorrentInfo torrentInfo, int pieceIndex) throws IOException, InterruptedException {
		awaitBitfield();
		sendInterested();

		final var fileLength = torrentInfo.length();
		final var pieceLength = torrentInfo.pieceLength();

		var realPieceLength = pieceLength;
		if (torrentInfo.pieces().size() - 1 == pieceIndex) {
			realPieceLength = (int) (fileLength % pieceLength);
		}

		final var pieceHash = torrentInfo.pieces().get(pieceIndex);

		final var bytes = new byte[realPieceLength];

		final var blockSize = (int) Math.pow(2, 14);
		var blockCount = 0;

		var blockStart = 0;
		for (; blockStart < realPieceLength - blockSize; blockStart += blockSize) {
			++blockCount;

			send(new Message.Request(
				pieceIndex,
				blockStart,
				blockSize
			));
		}

		final var remaining = realPieceLength - blockStart;
		if (remaining != 0) {
			++blockCount;

			send(new Message.Request(
				pieceIndex,
				blockStart,
				remaining
			));
		}

		int bytesDownloaded = 0;
		for (var index = 0; index < blockCount; ++index) {
			// final var piece = waitFor(Message.Piece.class, null);
			final var piece = pieceQueue.take(); // blocks until a piece arrives from the reader thread

			if(piece.index() != pieceIndex){
				System.err.println("Received piece for wrong index. Discarding.");
				index--; // decrement to re-wait for the correct piece.
				continue;
			}

			System.arraycopy(piece.block(), 0, bytes, piece.begin(), piece.block().length);
			bytesDownloaded += piece.block().length;
		}

		if(bytesDownloaded != realPieceLength){
			throw new IOException("Downloaded piece length mismatch.");
		}

		final var downloadedPieceHash = DigestUtils.sha1(bytes);
		if (!Arrays.equals(pieceHash, downloadedPieceHash)) {
			throw new IllegalStateException("piece hash does not match");
		}

		// Mark this piece as downloaded and verified in our bitfield
		clientBitfield.set(pieceIndex);

		// Send a HAVE message to this peer.
		// This tells the peer you now have this piece and can upload it.
		// Also notify all other connected peers about this new piece
		notifyAllPeersAboutNewPiece(pieceIndex); 
		send(new Message.Have(pieceIndex));

		return bytes;
	}
	
	/**
	 * Notify all connected peers (including incoming connections) about a newly available piece.
	 * This allows serving peers to update their bitfields and serve the piece immediately.
	 */
	private void notifyAllPeersAboutNewPiece(int pieceIndex) {
		try {
			List<Peer> allConnections = PeerConnectionManager.getInstance().getConnections(infoHashHex);
			for (Peer peer : allConnections) {
				// Skip ourselves
				if (peer == this) {
					continue;
				}
				
				// Update bitfield for incoming connections (serving peers)
				// This allows them to serve the piece immediately
				peer.markPieceAvailable(pieceIndex);
				
				// Send HAVE message to notify peer about new piece
				try {
					peer.send(new Message.Have(pieceIndex));
				} catch (IOException e) {
					// Peer connection might be closed, ignore
					if (BitTorrentApplication.DEBUG) {
						System.err.println("Failed to send HAVE to peer: " + e.getMessage());
					}
				}
			}
		} catch (Exception e) {
			// Ignore errors in notification
			if (BitTorrentApplication.DEBUG) {
				System.err.println("Error notifying peers about new piece: " + e.getMessage());
			}
		}
	}

	public byte[] downloadFile(TorrentInfo torrentInfo) throws IOException, InterruptedException {
		final var fileBytes = new ExposedByteArrayOutputStream((int) torrentInfo.length());
		for (var i = 0; i < torrentInfo.pieces().size(); ++i) {
			final var pieceBytes = downloadPiece(torrentInfo, i);
			fileBytes.write(pieceBytes);
		}
		return fileBytes.getBuffer();
	}

	public void sendInterested() throws IOException, InterruptedException {
		if (interested) {
			return;
		}

		send(new Message.Interested());
		this.interested = true;

		// old logic of blocked until unchoked is removed. 
		// a more advanced client would wait for an unchoke message.
		// (received by the reader loop) before sending 'Request' messages.
	}

	/**
	 * Sends a PEX update with the specified added and dropped peers.
	 * If parameters are null, automatically determines peers from SwarmManager.
	 */
	public void sendPexUpdate(List<InetSocketAddress> added, List<InetSocketAddress> dropped) throws IOException {
		if (pexExtensionId < 0 || closed) {
			return;
		}

		// If parameters are null, get peers from SwarmManager
		if (added == null && dropped == null) {
			var swarm = SwarmManager.getInstance();
			
			// Create exclusion set: current peer and our own listen address
			Set<InetSocketAddress> excludeAddresses = new HashSet<>();
			excludeAddresses.add(remoteAddress);
			
			// Exclude our own listen address (from socket's local address)
			InetSocketAddress localAddress = (InetSocketAddress) socket.getLocalSocketAddress();
			if (localAddress != null) {
				excludeAddresses.add(localAddress);
			}
			
			added = swarm.getPeersForPex(infoHashHex, excludeAddresses, 50);
			dropped = swarm.getDroppedPeers(infoHashHex, 50);
		}

		if ((added == null || added.isEmpty()) && (dropped == null || dropped.isEmpty())) {
			return;
		}

		if (added == null) {
			added = Collections.emptyList();
		}
		if (dropped == null) {
			dropped = Collections.emptyList();
		}

		var pex = new PexMessage.Pex(added, dropped);

		send(new Message.Extension(
			(byte) pexExtensionId,
			pex
		), extensionContext);

		if (BitTorrentApplication.DEBUG) {
			System.err.printf("Peer[%s]: sent PEX update (added: %d, dropped: %d)%n",
				remoteAddress, added.size(), dropped.size());
		}
	}

	/**
	 * Sends a PEX update with peers from SwarmManager (backward compatibility).
	 */
	public void sendPexUpdate() throws IOException {
		sendPexUpdate(null, null);
	}

	/**
	 * Gets the remote address of this peer connection.
	 */
	public InetSocketAddress getRemoteAddress() {
		return remoteAddress;
	}

	/**
	 * Checks if this peer connection is closed.
	 */
	public boolean isClosed() {
		return closed || socket.isClosed();
	}

	/**
	 * Runs periodic PEX updates.
	 */
	private void runPexUpdateLoop() {
		try {
			// Wait a bit before first update to let connection stabilize
			Thread.sleep(10_000); // 10 seconds

			while (!closed && !socket.isClosed() && !Thread.currentThread().isInterrupted()) {
				try {
					// Only send if PEX is negotiated
					if (pexExtensionId >= 0) {
						sendPexUpdate();
					}
				} catch (IOException e) {
					if (BitTorrentApplication.DEBUG) {
						System.err.printf("Peer[%s]: error sending PEX update: %s%n", remoteAddress, e.getMessage());
					}
					// If we can't send, the connection might be broken
					break;
				}

				// Wait for next update interval
				Thread.sleep(PEX_UPDATE_INTERVAL_MS);
			}
		} catch (InterruptedException e) {
			// Thread interrupted, exit
			Thread.currentThread().interrupt();
		} catch (Exception e) {
			if (BitTorrentApplication.DEBUG) {
				System.err.printf("Peer[%s]: error in PEX update loop: %s%n", remoteAddress, e.getMessage());
			}
		}
	}

	@Override
	public void close() throws IOException, InterruptedException {
		closed = true;
		
		// Unregister from PeerConnectionManager
		PeerConnectionManager.getInstance().unregisterConnection(infoHashHex, this);
		
		// Unregister from SwarmManager
		SwarmManager.getInstance().unregisterActivePeer(infoHashHex, remoteAddress);
		
		// Note: We keep upload stats even after peer disconnects for historical tracking
		// Stats are only cleared when seeding stops (via SeedingStatsService.clearTorrentStats)
		
		// Stop threads
		readerThread.interrupt();
		pexUpdateThread.interrupt();
		
		// Close socket
		socket.close();
		
		// Wait for threads to die
		readerThread.join(2000);
		pexUpdateThread.join(2000);
	}

	public static Peer connect(InetSocketAddress address, Announceable announceable, TorrentInfo torrentInfo, File file, String peerId) throws IOException {
		System.err.println("peer: trying to connect: %s".formatted(address));

		final var socket = new Socket(address.getAddress(), address.getPort());
		return connect(socket, announceable, torrentInfo, file, peerId);
	}

	public static Peer connect(Socket socket, Announceable announceable, TorrentInfo torrentInfo, File file, String peerId) throws IOException {
		final var infoHash = announceable.getInfoHash();
		// Always advertise extension support (bit 5 = 0x10) for PEX to work
		final var padding = PADDING_MAGNET_8; // Use extension-enabled padding for all connections

		try {
			final var inputStream = new DataInputStream(socket.getInputStream());
			final var outputStream = socket.getOutputStream();

			{
				/* length of the protocol string */
				outputStream.write(19);

				/* the string BitTorrent protocol */
				outputStream.write(PROTOCOL_BYTES);

				/* eight reserved bytes */
				outputStream.write(padding);

				/* sha1 infohash */
				outputStream.write(announceable.getInfoHash());

				/* peer id */
				outputStream.write(peerId.getBytes(StandardCharsets.US_ASCII));
			}

			{
				final var length = inputStream.readByte();
				if (length != 19) {
					throw new IllegalStateException("invalid protocol length: " + length);
				}

				final var receivedProtocol = inputStream.readNBytes(19);
				if (!Arrays.equals(receivedProtocol, PROTOCOL_BYTES)) {
					System.out.println(Main.HEX_FORMAT.formatHex(receivedProtocol));
					throw new IllegalStateException("invalid protocol string: " + new String(receivedProtocol));
				}

				/* padding */
				final var receivedPadding = inputStream.readNBytes(8);
				final var supportExtensions = receivedPadding[5] == 0x10; // TODO Bugged https://forum.codecrafters.io/t/pk2-reserved-bit-in-handshake-for-extension-protocol-seems-to-be-set-incorrectly-by-codecrafters-server/2461
				//				final var supportExtensions = announceable instanceof Magnet;
				System.err.println("peer: padding: %s".formatted(Main.HEX_FORMAT.formatHex(receivedPadding)));

				final var receivedInfoHash = inputStream.readNBytes(20);
				if (!Arrays.equals(receivedInfoHash, infoHash)) {
					throw new IllegalStateException("invalid info hash: " + Arrays.toString(infoHash));
				}
	
				final var receivedPeerId = inputStream.readNBytes(20);
				return new Peer(receivedPeerId, socket, supportExtensions, torrentInfo, file);
			}
		} catch (Exception exception) {
			socket.close();
			throw exception;
		}
	}

	public MetadataMessage sendMetadata(MetadataMessage message) throws IOException {
		if (metadataExtensionId < 0) {
			throw new IllegalStateException("Metadata extension not negotiated");
		}

		send(
			new Message.Extension(
				(byte) metadataExtensionId,
				message
			),
			extensionContext
		);

		Message.Extension ext = waitFor(
			Message.Extension.class,
			extensionContext
		);

		Object content = ext.content();
		if (content instanceof MetadataMessage metadataMessage) {
			return metadataMessage;
		}

		@SuppressWarnings("unchecked")
		var objects = (java.util.List<Object>) content;
		return bittorrent.peer.serial.extension.MetadataMessageSerial.deserialize(objects);
	}

	public TorrentInfo queryTorrentInfoViaMetadataExtension() throws IOException, InterruptedException {
		awaitBitfield();

		final var response = sendMetadata(new MetadataMessage.Request(0));
		if (!(response instanceof MetadataMessage.Data data)) {
			throw new IllegalStateException("no data found: %s".formatted(response));
		}

		return data.torrentInfo();
	}

	private void runReaderLoop() {
		try {
			final var dataInputStream = new DataInputStream(socket.getInputStream());
			
			while (!socket.isClosed() && !Thread.currentThread().isInterrupted()) {
				// 1. Read message length
				final int length;
				try {
					length = dataInputStream.readInt();
				} catch (EOFException exception) {
					throw new PeerClosedException(exception);
				}
	
				// 2. Read type and deserialize
				final var typeId = length != 0 ? dataInputStream.readByte() : (byte) -1;
				
				MessageDescriptor<?> descriptor;
				try {
					descriptor = MessageDescriptors.getByTypeId(typeId);
				} catch (IllegalArgumentException e) {
					// Unknown message type - skip this message
					if (BitTorrentApplication.DEBUG) {
						System.err.printf("Peer[%s]: Unknown message type id: %d (length: %d), skipping%n", 
							remoteAddress, typeId & 0xFF, length);
					}
					// Skip the remaining bytes of this message
					if (length > 1) {
						dataInputStream.skipBytes(length - 1);
					}
					continue;
				}

				MessageSerialContext context = null;
				if (descriptor.typeId() == MessageDescriptors.EXTENSION.typeId()) {
					context = extensionContext;
				}
				
				final var message = descriptor.deserialize(length - 1, dataInputStream, context);
				
				if (BitTorrentApplication.DEBUG) {
					System.err.println("RECV_LOOP: %s".formatted(message));
				}
				
				// 3. Handle KeepAlive directly
				if (message instanceof Message.KeepAlive) {
					send(new Message.KeepAlive()); // Respond immediately
					continue;
				}

				// 4. Pass to message handler
				handleMessage(message);
				
				// Notify any threads waiting for messages
				synchronized (receiveQueue) {
					receiveQueue.notifyAll();
				}
			}
		} catch (EOFException | PeerClosedException e) {
			if (BitTorrentApplication.DEBUG) {
				System.err.println("Peer connection closed: " + socket.getRemoteSocketAddress());
			}
		} catch (IOException e) {
			if (!socket.isClosed()) {
				System.err.println("Error in peer reader loop: " + e.getMessage());
			}
		} catch (RuntimeException e) {
			// Catch runtime exceptions (like IllegalArgumentException from getByTypeId)
			// Log but don't crash - the connection might still be valid
			if (BitTorrentApplication.DEBUG) {
				System.err.printf("Peer[%s]: Error in reader loop: %s%n", remoteAddress, e.getMessage());
			}
			// If it's a deserialization error, the connection might be corrupted, so close it
			if (e.getCause() instanceof IOException) {
				closeQuietly();
			}
		} finally {
			closeQuietly();
		}
	}

	private void handleMessage(Message message) throws IOException {
		if (message instanceof Message.Piece piece) {
			// This is for our download. Add it to the queue.
			pieceQueue.add(piece);
		} else if (message instanceof Message.Interested) {
			// The peer is interested in us.
			this.peerInterested = true;
			// If we are choking them, unchoke them so they can request.
			if (this.amChoking) {
				send(new Message.Unchoke());
				this.amChoking = false;
			}
		} else if (message instanceof Message.NotInterested) {
			// The peer is not interested.
			this.peerInterested = false;
		} else if (message instanceof Message.Request request) {
			// The peer is requesting a block. This is our upload logic.
			handlePieceRequest(request);
		} else if (message instanceof Message.Bitfield) {
			// This is for our download. Store it.
			this.bitfield = true;
			// Only add to queue if awaitBitfield() hasn't completed yet
			// (it will check the bitfield flag first)
			receiveQueue.add(message); // Also add to queue for awaitBitfield()
		} else if (message instanceof Message.Extension extension) {
			// Extension messages can be metadata (handshake / data) or PEX.
			byte extId = extension.id();

			// Handle extension handshake (id=0) - this sets extension IDs
			if (extId == 0) {
				// Process extension handshake to set extension IDs
				try {
					@SuppressWarnings("unchecked")
					var objects = (java.util.List<Object>) extension.content();
					var metadata = bittorrent.peer.serial.extension.MetadataMessageSerial.deserialize(objects);
					if (metadata instanceof MetadataMessage.Handshake handshake) {
						var ids = handshake.extensionIds();
						if (ids.containsKey("ut_metadata")) {
							metadataExtensionId = ids.get("ut_metadata");
							extensionContext.registerExtension((byte) metadataExtensionId, "ut_metadata");
						}
						if (ids.containsKey("ut_pex")) {
							pexExtensionId = ids.get("ut_pex");
							extensionContext.registerExtension((byte) pexExtensionId, "ut_pex");
						}
						if (BitTorrentApplication.DEBUG) {
							System.err.printf("Peer[%s]: processed extension handshake (metadata=%d, pex=%d)%n",
								remoteAddress, metadataExtensionId, pexExtensionId);
						}
						// Also queue for awaitBitfield() if it's waiting
						receiveQueue.add(extension);
						return;
					}
				} catch (Exception e) {
					if (BitTorrentApplication.DEBUG) {
						System.err.printf("Peer[%s]: error processing extension handshake: %s%n", remoteAddress, e.getMessage());
					}
					// Still queue it so awaitBitfield() can handle it
					receiveQueue.add(extension);
					return;
				}
			}

			// During initial handshake we just queue the message for awaitBitfield()
			// This handles the case where extension IDs aren't set yet
			if (metadataExtensionId == -1 && pexExtensionId == -1) {
				receiveQueue.add(extension);
				return;
			}

			if (extId == metadataExtensionId || extId == 0) {
				// Queue metadata-related messages for the metadata helpers
				receiveQueue.add(extension);
			} else if (extId == pexExtensionId) {
				@SuppressWarnings("unchecked")
				var objects = (java.util.List<Object>) extension.content();
				var pex = bittorrent.peer.serial.extension.PexMessageSerial.deserialize(objects);

				if (pex instanceof PexMessage.Pex pexMsg) {
					if (BitTorrentApplication.DEBUG) {
						int addedCount = pexMsg.added() != null ? pexMsg.added().size() : 0;
						int droppedCount = pexMsg.dropped() != null ? pexMsg.dropped().size() : 0;
						System.err.printf("Peer[%s]: received PEX update (added: %d, dropped: %d)%n",
							remoteAddress, addedCount, droppedCount);
					}
					
					// Add discovered peers to SwarmManager
					// Only broadcast peers that were actually NEW (not already known)
					if (pexMsg.added() != null && !pexMsg.added().isEmpty()) {
						// Get list of peers that were actually new (before adding)
						var swarm = SwarmManager.getInstance();
						List<InetSocketAddress> newPeers = new ArrayList<>();
						for (InetSocketAddress addr : pexMsg.added()) {
							// Check if peer was already known before adding
							if (!swarm.isPeerKnown(infoHashHex, addr)) {
								newPeers.add(addr);
							}
						}
						
						// Add all peers to SwarmManager
						swarm.onPexPeersDiscovered(infoHashHex, pexMsg.added());
						
						// Only broadcast peers that were actually new (to avoid loops)
						if (!newPeers.isEmpty()) {
							PeerConnectionManager.getInstance().broadcastNewPeers(infoHashHex, newPeers);
						}
					}
					
					// Handle dropped peers
					if (pexMsg.dropped() != null && !pexMsg.dropped().isEmpty()) {
						// Mark peers as dropped in SwarmManager
						for (InetSocketAddress addr : pexMsg.dropped()) {
							SwarmManager.getInstance().unregisterActivePeer(infoHashHex, addr);
						}
					}
				}
			} else {
				// Unknown extension ID for this connection – ignore for now.
				if (BitTorrentApplication.DEBUG) {
					System.err.println("Unknown extension id: " + extId);
				}
			}
		}
		// You can add handlers for Choke, Unchoke, Have, Cancel here
	}

	private void handlePieceRequest(Message.Request request) throws IOException {
		if (!peerInterested) {
			if (BitTorrentApplication.DEBUG) {
				System.err.println("Got request from uninterested peer. Ignoring.");
			}
			return; 
		}
		if (amChoking) {
			if (BitTorrentApplication.DEBUG) {
				System.err.println("Got request from choked peer. Ignoring.");
			}
			return;
		}
		
		// Check if we actually have this piece before attempting to upload
		if (!clientBitfield.get(request.index())) {
			if (BitTorrentApplication.DEBUG) {
				System.err.println("Got request for piece %d that we don't have. Ignoring.".formatted(request.index()));
			}
			return;
		}
		
		try (RandomAccessFile raf = new RandomAccessFile(this.downloadedFile, "r")) {
			long pieceStart = (long)request.index() * this.torrentInfo.pieceLength();
			long blockStart = pieceStart + request.begin();
			
			if (request.length() > 16384) { // 2^14 bytes
					System.err.println("Request length too large. Ignoring.");
					return;
			}

			byte[] block = new byte[request.length()];
			raf.seek(blockStart);
			int bytesRead = raf.read(block);

			if (bytesRead != request.length()) {
				System.err.println("Could not read full block from file. Ignoring request.");
				return;
			}
			
			// Send the requested piece
			send(new Message.Piece(request.index(), request.begin(), block));
			
			// Track upload statistics for seeding torrents
			try {
				bittorrent.service.SeedingStatsService statsService = 
					bittorrent.service.SeedingStatsService.getInstance();
				statsService.recordBytesUploaded(infoHashHex, remoteAddress, request.length());
				// Note: We track piece uploads per block, but could also track complete pieces
				// For now, tracking bytes is sufficient
			} catch (Exception e) {
				// Don't fail upload if stats tracking fails
				if (BitTorrentApplication.DEBUG) {
					System.err.println("Failed to record upload stats: " + e.getMessage());
				}
			}
			
		} catch (FileNotFoundException e) {
			// This will happen if we don't have the file/piece yet.
			if (BitTorrentApplication.DEBUG) {
				System.err.println("Cannot read from file to upload, file not found or piece not downloaded.");
			}
		} catch (IOException e) {
			System.err.println("Error reading from file for upload: " + e.getMessage());
		}
	}

	private void closeQuietly() {
		try {
			if (socket != null && !socket.isClosed()) {
				socket.close();
			}
		} catch (IOException e) {
			// Ignore
		}
	}
	
	
}