package net.albinoloverats.messaging.common;

import lombok.val;
import net.albinoloverats.messaging.common.security.TLSChannel;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ConnectionPendingException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Queue;
import java.util.UUID;

/**
 * Common code for messaging (Java NIO) client or server-side client
 * representation. This would be an abstract class if Java supported
 * multiple inheritance.
 */
public interface NIOClient
{
	/**
	 * Get the client ID.
	 *
	 * @return The Client ID.
	 */
	UUID getClientId();

	/**
	 * Get the write queue.
	 *
	 * @return The write queue.
	 */
	Queue<ByteBuffer> getWriteQueue();

	/**
	 * Get the TLS channel.
	 *
	 * @return The TLS channel.
	 */
	TLSChannel getTlsChannel();

	/**
	 * Get the Socket selector.
	 *
	 * @return The socket selector.
	 */
	Selector getSelector();

	/**
	 * Receive incoming data.
	 *
	 * @throws IOException Thrown if reading from TLS channel fails.
	 */
	default void receive() throws IOException
	{
		val tlsChannel = getTlsChannel();
		tlsChannel.read(this::handleIncomingMessage);
	}

	/**
	 * Process incoming message.
	 *
	 * @param buffer Raw message payload.
	 */
	void handleIncomingMessage(ByteBuffer buffer);

	/**
	 * Query write queue and send any available messages.
	 *
	 * @param key The socket selection key.
	 * @throws IOException Thrown if writing to the TLS channel fails.
	 */
	default void send(SelectionKey key) throws IOException
	{
		val selector = getSelector();
		val writeQueue = getWriteQueue();
		val tlsChannel = getTlsChannel();
		ByteBuffer buffer;
		drain:
		while ((buffer = writeQueue.peek()) != null)
		{
			do
			{
				if (tlsChannel.write(buffer) <= 0)
				{
					break drain;
				}
			}
			while (buffer.hasRemaining());
			writeQueue.poll();
		}
		key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
		selector.wakeup();
	}

	/**
	 * Pop the given message payload onto the write queue.
	 *
	 * @param buffer The message payload to send.
	 */
	default void sendMessage(ByteBuffer buffer)
	{
		val handshakeComplete = isHandshakeComplete();
		if (!handshakeComplete)
		{
			throw new ConnectionPendingException();
		}
		val selector = getSelector();
		val writeQueue = getWriteQueue();
		writeQueue.offer(buffer);
		val tlsChannel = getTlsChannel();
		val channel = tlsChannel.getChannel();
		val key = channel.keyFor(selector);
		if (key != null && key.isValid())
		{
			key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
			selector.wakeup();
		}
	}

	/**
	 * Indicate whether the SSL handshake is complete.
	 *
	 * @return True if the handshake is complete.
	 */
	default boolean isHandshakeComplete()
	{
		val tlsChannel = getTlsChannel();
		return tlsChannel.isHandshakeComplete();
	}

	/**
	 * Perform SSL handshake.
	 *
	 * @throws IOException Thrown if an error occurs during the handshake process.
	 */
	default void handshake() throws IOException
	{
		val tlsChannel = getTlsChannel();
		val selector = getSelector();
		tlsChannel.handshake(selector);
	}

	/**
	 * Close both selector key and TLS channel.
	 */
	default void close()
	{
		val selector = getSelector();
		val tlsChannel = getTlsChannel();
		val channel = tlsChannel.getChannel();
		val key = channel.keyFor(selector);
		if (key != null && key.isValid())
		{
			key.cancel();
		}
		tlsChannel.close();
	}
}
