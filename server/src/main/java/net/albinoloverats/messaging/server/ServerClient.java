package net.albinoloverats.messaging.server;

import com.jcabi.aspects.Loggable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import net.albinoloverats.messaging.common.NIOClient;
import net.albinoloverats.messaging.common.security.TLSChannel;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Slf4j
@ToString(onlyExplicitlyIncluded = true)
class ServerClient implements NIOClient
{
	@Getter
	private final Queue<ByteBuffer> writeQueue = new ConcurrentLinkedQueue<>();
	@Getter
	private final TLSChannel tlsChannel;
	@Getter
	private final SocketChannel channel;

	@Getter
	@Setter(AccessLevel.PACKAGE)
	@ToString.Include(rank = 2)
	private UUID clientId;
	@Getter(AccessLevel.PACKAGE)
	@Setter(AccessLevel.PACKAGE)
	@ToString.Include(rank = 3)
	private String serviceName;

	@Getter(AccessLevel.PACKAGE)
	@Setter(AccessLevel.PACKAGE)
	@ToString.Include
	private boolean isPeer;

	private final MessagingServer server;

	/*
	 * The keys here are the type; it's the opposite way round to the clients
	 * registration request: here it's [ Type : Handler ID(s) ]
	 */
	@Getter(AccessLevel.PACKAGE)
	@Setter(AccessLevel.PACKAGE)
	private Set<String> eventSubscriptions = new HashSet<>();
	@Getter(AccessLevel.PACKAGE)
	@Setter(AccessLevel.PACKAGE)
	private Set<String> querySubscriptions = new HashSet<>();
	@Getter(AccessLevel.PACKAGE)
	private final Set<UUID> awaitingResponse = ConcurrentHashMap.newKeySet();

	ServerClient(MessagingServer server, SocketChannel socketChannel, TLSChannel tlsChannel)
	{
		this.server = server;
		channel = socketChannel;
		this.tlsChannel = tlsChannel;
	}

	@Loggable(value = Loggable.TRACE, prepend = true)
	public void handleRead() throws IOException
	{
		receive();
	}

	@Override
	@Loggable(value = Loggable.TRACE, prepend = true)
	public void handleIncomingMessage(ByteBuffer buffer)
	{
		server.handleIncomingMessage(this, buffer, true);
	}

	@Loggable(value = Loggable.TRACE, prepend = true)
	public void handleWrite() throws IOException
	{
		val selector = getSelector();
		val selectionKey = channel.keyFor(selector);
		send(selectionKey);
	}

	@Override
	public Selector getSelector()
	{
		return server.getSelector();
	}

	@Loggable(value = Loggable.TRACE, prepend = true)
	public void disconnect()
	{
		tlsChannel.close();
		val selector = getSelector();
		val selectionKey = channel.keyFor(selector);
		if (selectionKey != null && selectionKey.isValid())
		{
			selectionKey.cancel();
		}
	}

	/*
	 * Various utility methods
	 */

	boolean hasChannel(SocketChannel channel)
	{
		return this.channel.equals(channel);
	}

	boolean hasSubscribersFor(String type)
	{
		return canHandleEvent(type) || canHandleQuery(type);
	}

	boolean canHandleEvent(String type)
	{
		return eventSubscriptions.contains(type);
	}

	boolean canHandleQuery(String type)
	{
		return querySubscriptions.contains(type);
	}

	boolean isWaitingFor(UUID queryId)
	{
		return queryId != null && awaitingResponse.contains(queryId);
	}

	void waitingFor(UUID queryId)
	{
		awaitingResponse.add(queryId);
	}

	void noLongerWaitingFor(UUID queryId)
	{
		awaitingResponse.remove(queryId);
	}
}

