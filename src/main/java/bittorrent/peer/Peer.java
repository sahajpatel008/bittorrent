package bittorrent.peer;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Deque;
import java.util.LinkedList;
import java.util.Map;
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

	private static final MessageSerialContext METADATA_CONTEXT = new MessageSerialContext(MetadataMessage.class);

	private final @Getter byte[] id;
	private final Socket socket;
	private final boolean supportExtensions;

	private boolean bitfield;
	private boolean interested;
	private @Getter int metadataExtensionId = -1;

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

	public Peer(byte[] id, Socket socket, boolean supportExtensions, TorrentInfo torrentInfo, File downloadedFile) {
		this.id = id;
		this.socket = socket;
		this.supportExtensions = supportExtensions;
		this.torrentInfo = torrentInfo; 
		this.downloadedFile = downloadedFile; 

		this.receiveQueue = new LinkedList<>();

		// Initialize bitfield with the number of pieces in the torrent
		this.clientBitfield = new BitSet(torrentInfo.pieces().size());

		// Start the reader thread
		this.readerThread = new Thread(this::runReaderLoop);
		this.readerThread.setName("PeerReader-" + socket.getRemoteSocketAddress());
		this.readerThread.setDaemon(true);
		this.readerThread.start();

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
		final var iterator = receiveQueue.iterator();
		while (iterator.hasNext()) {
			final var message = iterator.next();

			if (predicate.test(message)) {
				System.err.println("wait for: found: message=%s".formatted(message));

				iterator.remove();
				return message;
			}
		}

		while (true) {
			final var message = receive(false, context);

			if (predicate.test(message)) {
				return message;
			}

			System.err.println("wait for: push: message=%s".formatted(message));
			receiveQueue.add(message);
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
		if (bitfield) {
			return;
		}

		if (supportExtensions) {
			send(
				new Message.Extension(
					(byte) 0,
					new MetadataMessage.Handshake(Map.of(
						"ut_metadata", 42
					))
				),
				METADATA_CONTEXT
			);
			
			// This part should still work as handleMessage adds Extensions to receiveQueue
			final var extension = waitFor(Message.Extension.class, METADATA_CONTEXT);
			System.err.println("extension: %s".formatted(extension));

			final var metadata = (MetadataMessage.Handshake) extension.content();
			metadataExtensionId = metadata.extensionIds().get("ut_metadata");
		}

		// waitFor(Message.Bitfield.class, null);
		// NEW:  wait until the reader thread sets the bitfield flag
		while(!bitfield){
			// check receive queue for bitfield msg
			var msg = receiveQueue.poll();
			if(msg instanceof Message.Bitfield bf){
				handleMessage(bf); // process it
				break;
			}
			if(msg != null){
				receiveQueue.addFirst(msg); // put it back
			}

			Thread.sleep(100); // Poll
		}

		// Send our bitfield to the peer to let them know what pieces we have
		sendOurBitfield();
	}

	/**
	 * Sends our bitfield to the peer, indicating which pieces we have.
	 * Only sends if we have at least one piece.
	 */
	private void sendOurBitfield() throws IOException {
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
		send(new Message.Have(pieceIndex));

		return bytes;
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

	@Override
	public void close() throws IOException, InterruptedException {
		readerThread.interrupt(); // Stop the reader thread
		socket.close();
		readerThread.join(2000); // wait for thread to die.
	}

	public static Peer connect(InetSocketAddress address, Announceable announceable, TorrentInfo torrentInfo, File file) throws IOException {
		System.err.println("peer: trying to connect: %s".formatted(address));

		final var socket = new Socket(address.getAddress(), address.getPort());
		return connect(socket, announceable, torrentInfo, file); // Pass new args
	}

	public static Peer connect(Socket socket, Announceable announceable, TorrentInfo torrentInfo, File file) throws IOException {
		final var infoHash = announceable.getInfoHash();
		final var padding = announceable instanceof Magnet ? PADDING_MAGNET_8 : PADDING_8;

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
				outputStream.write("42112233445566778899".getBytes(StandardCharsets.US_ASCII));
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

				final var peerId = inputStream.readNBytes(20);
				return new Peer(peerId, socket, supportExtensions, torrentInfo, file);
			}
		} catch (Exception exception) {
			socket.close();
			throw exception;
		}
	}

	public MetadataMessage sendMetadata(MetadataMessage message) throws IOException {
		send(
			new Message.Extension(
				(byte) metadataExtensionId,
				message
			),
			METADATA_CONTEXT
		);

		return (MetadataMessage) waitFor(
			Message.Extension.class,
			METADATA_CONTEXT
		).content();
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
				final var descriptor = MessageDescriptors.getByTypeId(typeId);

				MessageSerialContext context = null;
				if (descriptor.typeId() == MessageDescriptors.EXTENSION.typeId()) {
						context = METADATA_CONTEXT;
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
			}
		} catch (EOFException | PeerClosedException e) {
			if (BitTorrentApplication.DEBUG) {
				System.err.println("Peer connection closed: " + socket.getRemoteSocketAddress());
			}
		} catch (IOException e) {
			if (!socket.isClosed()) {
				System.err.println("Error in peer reader loop: " + e.getMessage());
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
			receiveQueue.add(message); // Also add to queue for awaitBitfield()
		} else if (message instanceof Message.Extension) {
			receiveQueue.add(message); // Add to queue for awaitBitfield()
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