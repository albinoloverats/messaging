package net.albinoloverats.messaging.common.security;

import com.jcabi.aspects.Loggable;
import lombok.EqualsAndHashCode;
import lombok.EqualsAndHashCode.Include;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import net.albinoloverats.messaging.common.functions.QuietRunnable;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLException;
import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static net.albinoloverats.messaging.common.utils.Constants.READ_BUFFER_SIZE;
import static net.albinoloverats.messaging.common.utils.Constants.TLS_BUFFER_SIZE;

/**
 * Custom TLS wrapper for {@link SocketChannel}.
 */
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Slf4j
public class TLSChannel
{
	@Include
	@Getter
	private final SocketChannel channel;
	private final Selector selector;

	private final SSLEngine engine;
	private final ByteBuffer netIn;
	private final ByteBuffer netOut;
	private final ByteBuffer appIn;

	protected AtomicBoolean handshakeComplete = new AtomicBoolean(false);
	private final Consumer<SocketChannel> onHandshakeComplete;

	private final ByteBuffer readBuffer = ByteBuffer.allocateDirect(READ_BUFFER_SIZE);
	private final Queue<ByteBuffer> writeQueue = new ArrayDeque<>();
	private final Queue<ByteBuffer> slices = new ArrayDeque<>();

	/**
	 * Default (only) constructor. Create a new TLS channel for the given socket channel/selector.
	 *
	 * @param channel             The underlying socket channel.
	 * @param selector            The underlying selector.
	 * @param engine              The underlying SSL engine.
	 * @param onHandshakeComplete The method to run when the handshake is complete.
	 * @throws SSLException Thrown if there is an error initiating the handshake.
	 */
	public TLSChannel(SocketChannel channel, Selector selector, SSLEngine engine, Consumer<SocketChannel> onHandshakeComplete)
			throws SSLException
	{
		this.channel = channel;
		this.selector = selector;
		this.engine = engine;
		this.onHandshakeComplete = onHandshakeComplete;
		val session = engine.getSession();
		netIn = ByteBuffer.allocate(session.getPacketBufferSize());
		netOut = ByteBuffer.allocate(session.getPacketBufferSize());
		appIn = ByteBuffer.allocate(session.getApplicationBufferSize());
		engine.beginHandshake();
	}

	/*
	 * SSL handshake
	 */

	/**
	 * Perform next handshake step.
	 *
	 * @param selector The underlying selector. TODO: check if this is necessary.
	 * @throws IOException Thrown if the wrap/unwrap step fails.
	 */
	@Loggable(value = Loggable.TRACE, prepend = true)
	public void handshake(Selector selector) throws IOException
	{
		if (handshakeComplete.get())
		{
			return;
		}
		val key = channel.keyFor(selector);
		val interestOps = key.interestOps();
		switch (engine.getHandshakeStatus())
		{
			case NEED_UNWRAP ->
			{
				log.debug("Handshake unwrapping");
				if (!unwrap())
				{
					key.interestOps(interestOps | SelectionKey.OP_READ);
					selector.wakeup();
				}
			}
			case NEED_WRAP ->
			{
				log.debug("Handshake wrapping");
				if (!wrap())
				{
					key.interestOps(interestOps | SelectionKey.OP_WRITE);
					selector.wakeup();
				}
			}
			case NEED_TASK ->
			{
				log.debug("Handshake running delegate task");
				runDelegatedTasks();
			}
			case FINISHED, NOT_HANDSHAKING ->
			{
				log.debug("Handshake complete");
				key.interestOps(interestOps & ~SelectionKey.OP_WRITE);
				selector.wakeup();
				completeHandshake();
			}
		}
	}

	@Loggable(value = Loggable.TRACE, prepend = true)
	private void completeHandshake()
	{
		if (!handshakeComplete.getAndSet(true))
		{
			netIn.clear();
			netOut.clear();
			appIn.clear();
			onHandshakeComplete.accept(channel);
		}
	}

	@Loggable(value = Loggable.TRACE, prepend = true)
	private boolean unwrap() throws IOException
	{
		val bytesRead = channel.read(netIn);
		if (bytesRead < 0)
		{
			QuietRunnable.doQuietly(engine::closeInbound);
			QuietRunnable.doQuietly(engine::closeOutbound);
			throw new ClosedChannelException();
		}
		else if (bytesRead == 0 && !netIn.hasRemaining())
		{
			return false;
		}
		netIn.flip();
		val result = engine.unwrap(netIn, appIn);
		netIn.compact();
		val unwrapStatus = result.getStatus();
		return switch (unwrapStatus)
		{
			case OK ->
			{
				val handshakeStatus = result.getHandshakeStatus();
				if (handshakeStatus == HandshakeStatus.FINISHED)
				{
					completeHandshake();
				}
				yield true;
			}
			case CLOSED ->
			{
				log.warn("Handshake unwrap attempted to read from a closed channel");
				throw new ClosedChannelException();
			}
			case BUFFER_UNDERFLOW -> false;
			case BUFFER_OVERFLOW ->
			{
				log.error("Handshake unwrap failed due to buffer overflow");
				throw new BufferOverflowException();
			}
		};
	}

	@Loggable(value = Loggable.TRACE, prepend = true)
	private boolean wrap() throws IOException
	{
		netOut.clear();
		val result = engine.wrap(ByteBuffer.allocate(0), netOut);
		netOut.flip();
		val wrapStatus = result.getStatus();
		return switch (wrapStatus)
		{
			case OK:
				while (netOut.hasRemaining())
				{
					val written = channel.write(netOut);
					if (written == 0)
					{
						yield false;
					}
				}
				val handshakeStatus = result.getHandshakeStatus();
				if (handshakeStatus == HandshakeStatus.FINISHED)
				{
					completeHandshake();
					yield true;
				}
				yield false;
			case CLOSED:
				log.error("Handshake wrap attempted to write to a closed channel");
				throw new ClosedChannelException();
			case BUFFER_OVERFLOW:
				log.error("Handshake wrap failed due to buffer overflow");
				throw new BufferOverflowException();
			default:
				log.error("Unexpected failure during handshake wrap: {}", wrapStatus);
				throw new IOException("Unexpected wrap status: " + wrapStatus);
		};
	}

	@Loggable(value = Loggable.TRACE, prepend = true)
	private void runDelegatedTasks()
	{
		Runnable task;
		while ((task = engine.getDelegatedTask()) != null)
		{
			task.run();
		}
	}

	/**
	 * Indicate whether the handshake is complete or not.
	 *
	 * @return True if the handshake is complete.
	 */
	@Loggable(value = Loggable.TRACE, prepend = true)
	public boolean isHandshakeComplete()
	{
		return handshakeComplete.get();
	}

	@Loggable(value = Loggable.TRACE, prepend = true)
	private void verifyHandshakeComplete()
	{
		if (!handshakeComplete.get())
		{
			throw new IllegalStateException("Handshake not complete");
		}
	}

	/*
	 * Standard IO
	 */

	/**
	 * Read data from channel.
	 *
	 * @param onRead Method to run when data is read.
	 * @throws IOException Thrown on read error.
	 */
	@Loggable(value = Loggable.TRACE, prepend = true)
	public void read(Consumer<ByteBuffer> onRead) throws IOException
	{
		verifyHandshakeComplete();
		val bytesRead = read(readBuffer);
		if (bytesRead == 0)
		{
			return;
		}
		if (bytesRead == -1)
		{
			throw new ClosedChannelException();
		}
		if (readBuffer.position() < Integer.BYTES)
		{
			return;
		}

		readBuffer.flip();
		while (readBuffer.remaining() >= Integer.BYTES)
		{
			readBuffer.mark();
			val messageLength = readBuffer.getInt();
			if (messageLength < 0 || messageLength > READ_BUFFER_SIZE)
			{
				throw new IOException("Invalid data length");
			}
			if (readBuffer.remaining() >= messageLength)
			{
				val messagePayload = ByteBuffer.allocate(messageLength + Integer.BYTES);
				messagePayload.putInt(messageLength);
				val bytes = new byte[messageLength];
				readBuffer.get(bytes);
				messagePayload.put(bytes);
				messagePayload.flip();
				onRead.accept(messagePayload);
			}
			else
			{
				readBuffer.reset();
				break;
			}
		}
		readBuffer.compact();
	}

	@Loggable(value = Loggable.TRACE, prepend = true)
	private int read(ByteBuffer buffer) throws IOException
	{
		if (channel.read(netIn) < 0)
		{
			engine.closeInbound();
			return -1;
		}
		netIn.flip();
		var bytesRead = 0;
		var progress = true;
		while (progress)
		{
			progress = false;

			val result = engine.unwrap(netIn, appIn);
			if (result.bytesConsumed() > 0 || result.bytesProduced() > 0)
			{
				progress = true;
			}
			val unwrapStatus = result.getStatus();
			switch (unwrapStatus)
			{
				case OK ->
				{
					appIn.flip();
					int toCopy = Math.min(appIn.remaining(), buffer.remaining());
					buffer.put(appIn.slice().limit(toCopy));
					appIn.position(appIn.position() + toCopy);
					appIn.compact();
					bytesRead += toCopy;
					if (netIn.hasRemaining())
					{
						progress = true;
					}
				}
				case CLOSED ->
				{
					log.error("Read failed due to closed channel");
					throw new ClosedChannelException();
				}
				case BUFFER_OVERFLOW ->
				{
					log.error("Read failed due to buffer overflow");
					throw new BufferOverflowException();
				}
				case BUFFER_UNDERFLOW ->
				{
					val key = channel.keyFor(selector);
					val interestOps = key.interestOps();
					key.interestOps(interestOps | SelectionKey.OP_READ);
					selector.wakeup();
				}
			}
		}
		netIn.compact();
		return bytesRead;
	}

	/**
	 * Write buffer to channel.
	 *
	 * @param buffer The payload to write.
	 * @return The number of bytes written.
	 * @throws IOException Thrown on write error.
	 */
	@Loggable(value = Loggable.TRACE, prepend = true)
	public int write(ByteBuffer buffer) throws IOException
	{
		var bytesWritten = 0;
		// Drain any pending network data first
		while (!writeQueue.isEmpty())
		{
			val buf = writeQueue.peek();
			val w = channel.write(buf);
			if (w == 0)
			{
				val key = channel.keyFor(selector);
				val interestOps = key.interestOps();
				key.interestOps(interestOps | SelectionKey.OP_WRITE);
				selector.wakeup();
				return bytesWritten;
			}
			bytesWritten += w;
			if (!buf.hasRemaining())
			{
				writeQueue.poll();
			}
		}
		// Wrap application data in TLS chunks
		while (!slices.isEmpty() || buffer.hasRemaining())
		{
			ByteBuffer slice;
			if (!slices.isEmpty())
			{
				slice = slices.peek();
			}
			else
			{
				val chunkSize = Math.min(buffer.remaining(), TLS_BUFFER_SIZE);
				slice = buffer.slice();
				slice.limit(chunkSize);
				slices.add(slice);
			}

			netOut.clear();
			val result = engine.wrap(slice, netOut);
			netOut.flip();

			val status = result.getStatus();
			switch (status)
			{
				case OK ->
				{
					while (netOut.hasRemaining())
					{
						val w = channel.write(netOut);
						bytesWritten += w;
						if (w == 0)
						{
							// save remaining netOut for later
							val remaining = ByteBuffer.allocate(netOut.remaining());
							remaining.put(netOut)
									.flip();
							writeQueue.add(remaining);

							val key = channel.keyFor(selector);
							val interestOps = key.interestOps();
							key.interestOps(interestOps | SelectionKey.OP_WRITE);
							selector.wakeup();
							return bytesWritten;
						}
					}
					if (!slice.hasRemaining())
					{
						slices.poll();
						buffer.position(buffer.position() + slice.limit());
					}
				}
				case CLOSED ->
				{
					log.error("Write failed due to closed channel");
					throw new ClosedChannelException();
				}
				case BUFFER_OVERFLOW ->
				{
					log.error("Write failed due to buffer overflow");
					throw new BufferOverflowException();
				}
				default ->
				{
					log.error("Unexpected failure during write wrap: {}", status);
					throw new IOException("Unexpected wrap status: " + status);
				}
			}
		}
		return bytesWritten;
	}

	/*
	 * Pass-through methods
	 */

	/**
	 * Close the TLS channel, associated buffers, and underlying socket channel.
	 */
	@Loggable(value = Loggable.TRACE, prepend = true)
	public void close()
	{
		handshakeComplete.set(false);
		writeQueue.clear();
		readBuffer.clear();
		netIn.clear();
		netOut.clear();
		appIn.clear();
		QuietRunnable.doQuietly(engine::closeInbound);
		QuietRunnable.doQuietly(engine::closeOutbound);
		QuietRunnable.doQuietly(channel::close);
	}
}
