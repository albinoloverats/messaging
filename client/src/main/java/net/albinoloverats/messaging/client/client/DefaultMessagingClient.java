package net.albinoloverats.messaging.client.client;

import com.jcabi.aspects.Loggable;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import net.albinoloverats.messaging.client.config.AnnotatedEventDispatcher;
import net.albinoloverats.messaging.client.exceptions.ConnectionLostException;
import net.albinoloverats.messaging.client.exceptions.QueryException;
import net.albinoloverats.messaging.client.metrics.Counters;
import net.albinoloverats.messaging.client.utils.ExceptionReplicator;
import net.albinoloverats.messaging.common.NIO;
import net.albinoloverats.messaging.common.NIOClient;
import net.albinoloverats.messaging.common.config.MessagingProperties;
import net.albinoloverats.messaging.common.exceptions.HandlerNotFound;
import net.albinoloverats.messaging.common.exceptions.InvalidMessage;
import net.albinoloverats.messaging.common.functions.QuietRunnable;
import net.albinoloverats.messaging.common.messages.ClientAck;
import net.albinoloverats.messaging.common.messages.MessageType;
import net.albinoloverats.messaging.common.messages.ServerAck;
import net.albinoloverats.messaging.common.messages.SubscribeToEvents;
import net.albinoloverats.messaging.common.security.TLSChannel;
import net.albinoloverats.messaging.common.utils.Constants;
import net.albinoloverats.messaging.common.utils.MessageSerialiser;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import tools.jackson.core.JacksonException;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import static net.albinoloverats.messaging.common.metrics.Constants.OUTBOUND_QUEUE;
import static net.albinoloverats.messaging.common.metrics.Constants.QUERY_DURATION;
import static net.albinoloverats.messaging.common.metrics.Constants.QUERY_PENDING;
import static net.albinoloverats.messaging.common.utils.Constants.DISCOVERY_BUFFER_SIZE;
import static net.albinoloverats.messaging.common.utils.Constants.FIVE_SECONDS;
import static net.albinoloverats.messaging.common.utils.Constants.ONE_SECOND;
import static net.albinoloverats.messaging.common.utils.Constants.PORT;
import static net.albinoloverats.messaging.common.utils.Constants.QUEUE_SIZE;

@Slf4j
public final class DefaultMessagingClient extends NIO<SocketChannel> implements MessagingClient, NIOClient, ApplicationListener<ContextRefreshedEvent>
{
	/*
	 * Us
	 */
	@Getter
	private final String clientName;
	@Getter
	private final UUID clientId;

	@Getter
	private final Queue<ByteBuffer> writeQueue = new LinkedBlockingDeque<>(QUEUE_SIZE);
	@Getter
	private TLSChannel tlsChannel;
	private final AnnotatedEventDispatcher eventDispatcher;

	private final AtomicBoolean dispatcherReady = new AtomicBoolean(false);
	private final AtomicBoolean initialSubscriptionSent = new AtomicBoolean(false);

	private final Map<UUID, CompletableFuture<Void>> awaitingAck = new ConcurrentHashMap<>();
	private final Map<UUID, Pair<Instant, CompletableFuture<Object>>> awaitingResponse = new ConcurrentHashMap<>();

	private final Timer queryTimer;

	private final Map<String, BiConsumer<UUID, Object>> messageHandlers = Map.ofEntries(
			Map.entry(ServerAck.class.getName(),
					(ignored, message) -> handleServerAck((ServerAck)message)),
			Map.entry(ClientAck.class.getName(),
					(id, message) -> handleClientAck(id, (ClientAck)message)),
			Map.entry(HandlerNotFound.class.getName(),
					(id, message) -> handleHandlerNotFound(id, (HandlerNotFound)message)),
			Map.entry(QueryException.class.getName(),
					(id, message) -> handleQueryException(id, (QueryException)message)));

	public DefaultMessagingClient(String serviceName,
	                              MessagingProperties properties,
	                              SSLContext sslContext,
	                              AnnotatedEventDispatcher eventDispatcher,
	                              MeterRegistry meterRegistry)
			throws IOException
	{
		val id = UUID.randomUUID();
		val counters = new Counters(id, meterRegistry, serviceName);
		val configuredHosts = properties.hosts()
				.stream()
				.map(StringUtils::toRootLowerCase)
				.collect(Collectors.toSet());

		super(configuredHosts, sslContext, counters);

		clientId = id;
		clientName = serviceName;

		this.eventDispatcher = eventDispatcher;

		val tags = counters.defaultTags();
		queryTimer = Timer.builder(QUERY_DURATION)
				.tags(tags)
				.register(meterRegistry);
	}

	protected void additionalMetrics(MeterRegistry meterRegistry)
	{
		val tags = counters.defaultTags();
		meterRegistry.gaugeMapSize(QUERY_PENDING, tags, awaitingResponse);
		meterRegistry.gaugeCollectionSize(OUTBOUND_QUEUE, tags, writeQueue);
	}

	@Override
	@Loggable(value = Loggable.TRACE, prepend = true)
	public void start() throws IOException
	{
		if (autoDiscover)
		{
			autoDiscovery();
		}
		else
		{
			sp2();
		}
	}

	private void sp2() throws IOException
	{
		connect();
		super.start();

		log.info("MessagingClient for {}-{} started", clientName, clientId);
	}

	@Loggable(value = Loggable.DEBUG, prepend = true)
	protected void handleDiscoveryMessage(DatagramChannel channel) throws IOException
	{
		val buffer = ByteBuffer.allocate(DISCOVERY_BUFFER_SIZE);
		val peerAddress = channel.receive(buffer);
		if (peerAddress instanceof InetSocketAddress address)
		{
			val host = address.getAddress().getHostAddress();
			if (knownHosts.contains(host))
			{
				return;
			}
			buffer.flip();
			val bytes = new byte[buffer.remaining()];
			buffer.get(bytes);
			serverId = UUID.fromString(new String(bytes));
			knownHosts.add(host);
		}
	}

	private void autoDiscovery()
	{
		log.info("Using auto-discovery for any servers on the network");
		val finishAt = Instant.now()
				.plus(FIVE_SECONDS);
		scheduledExecutor.schedule(() ->
		{
			do
			{
				try
				{
					selector.select(ONE_SECOND.toMillis());
					val selectedKeys = selector.selectedKeys();
					val iterator = selectedKeys.iterator();
					while (iterator.hasNext())
					{
						val key = iterator.next();
						iterator.remove();
						try
						{
							val channel = key.channel();
							if (channel instanceof DatagramChannel datagramChannel && key.isReadable())
							{
								handleDiscoveryMessage(datagramChannel);
							}
						}
						catch (IOException e)
						{
							log.error("Error handling server discovery message", e);
						}
					}
				}
				catch (IOException e)
				{
					log.error("Could not listen for broadcast messages", e);
				}
			}
			while (Instant.now().isBefore(finishAt));
		}, 0, TimeUnit.SECONDS);

		scheduledExecutor.schedule(() ->
		{
			//QuietRunnable.doQuietly(selector::close);
			QuietRunnable.doQuietly(discoveryChannel::close);
			if (knownHosts.isEmpty())
			{
				log.error("No servers found during auto-discovery");
				onDisconnect(null);
				return;
			}
			log.info("Found {} servers : {}", knownHosts.size(), knownHosts);
			try
			{
				sp2();
			}
			catch (IOException e)
			{
				throw new RuntimeException(e);
			}
		}, Constants.TEN_SECONDS.getSeconds(), TimeUnit.SECONDS);
	}

	@Loggable(value = Loggable.TRACE, prepend = true)
	private void connect() throws IOException
	{
		val asList = new ArrayList<>(knownHosts);
		Collections.shuffle(asList);
		val host = asList.getFirst();

		log.info("Connecting to {}", host);
		channel = SocketChannel.open();
		channel.configureBlocking(false);
		channel.register(selector, SelectionKey.OP_CONNECT);
		val socketAddress = new InetSocketAddress(host, PORT);
		channel.connect(socketAddress);
		tlsChannel = createTlsChannel();
	}

	public boolean isConnected()
	{
		return channel != null && channel.isConnected() && isHandshakeComplete();
	}

	@Override
	@Loggable(value = Loggable.TRACE, prepend = true)
	protected void onHandshakeComplete(SocketChannel channel)
	{
		SocketAddress address = null;
		try
		{
			address = channel.getRemoteAddress();
		}
		catch (IOException e)
		{
			log.warn("Could not get address for channel : {}", channel);
		}
		log.info("Handshake complete with {}", address);
		if (dispatcherReady.get())
		{
			reSubscribe();
		}
	}

	@Override
	@Loggable(value = Loggable.TRACE, prepend = true)
	protected void onKeyAcceptable(SelectionKey key)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	@Loggable(value = Loggable.TRACE, prepend = true)
	protected void onKeyConnectable(SocketChannel channel) throws IOException
	{
		if (channel.isConnectionPending())
		{
			channel.finishConnect();
			channel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE, this);
			handshake();
		}
	}

	@Override
	@Loggable(value = Loggable.TRACE, prepend = true)
	protected void onKeyReadable(SelectionKey key) throws IOException
	{
		receive();
	}

	@Override
	@Loggable(value = Loggable.TRACE, prepend = true)
	protected void onKeyWriteable(SelectionKey key) throws IOException
	{
		send(key);
	}

	@Override
	@Loggable(value = Loggable.TRACE, prepend = true)
	protected void onDisconnect(Object attachment)
	{
		if (attachment != null && attachment != this)
		{
			log.warn("Unexpected attachment {} for {}", attachment, this);
		}
		stop();
		awaitingResponse.forEach((correlationId, pair) ->
		{
			val future = pair.getRight();
			future.completeExceptionally(new ConnectionLostException("Future failed - disconnected from server"));
		});
		awaitingResponse.clear();
		scheduleReconnect();
	}

	@Loggable(value = Loggable.TRACE, prepend = true)
	private void scheduleReconnect()
	{
		scheduledExecutor.schedule(() ->
		{
			if (isConnected())
			{
				return;
			}
			try
			{
				log.info("Attempting reconnect...");
				start();
			}
			catch (IOException e)
			{
				log.warn("Reconnect attempt failed", e);
				scheduleReconnect();
			}
		}, FIVE_SECONDS.getSeconds(), TimeUnit.SECONDS);
	}

	/*
	 * Subscription handling
	 */

	@Override
	@Loggable(value = Loggable.TRACE, prepend = true)
	public void onApplicationEvent(ContextRefreshedEvent event)
	{
		/*
		 * Ensure this only acts for the root application context, not child contexts in complex setups
		 */
		if (event.getApplicationContext().getParent() == null)
		{
			log.debug("ContextRefreshedEvent received. Checking if client should subscribe.");
			dispatcherReady.set(true);
			/*
			 * This ensures the initial subscription happens ONLY once after the context is fully loaded AND if the
			 * client is already connected.
			 */
			if (isConnected() && initialSubscriptionSent.compareAndSet(false, true))
			{
				log.debug("Client connected and context refreshed. Sending initial subscription.");
				reSubscribe();
			}
			else if (!isConnected())
			{
				log.debug("Client not yet connected when context refreshed. Subscription will be sent upon successful connection.");
			}
		}
	}

	@Loggable(value = Loggable.TRACE, prepend = true)
	private void reSubscribe()
	{
		val subscribedEventTypes = eventDispatcher.getSubscribedEventTypes();
		val subscribedQueryTypes = eventDispatcher.getSubscribedQueryTypes();

		val allTypes = new HashSet<>(subscribedEventTypes);
		allTypes.addAll(subscribedQueryTypes);
		/*
		 * Re-subscribes to all previously subscribed event types. Called on reconnect.
		 */
		if (!allTypes.isEmpty())
		{
			val request = new SubscribeToEvents(clientName, clientId, subscribedEventTypes, subscribedQueryTypes);
			sendMessage(request);
			log.debug("Client {}-{} re-subscribed to event types: {}", clientName, clientId, allTypes);
		}
		else
		{
			log.debug("Client {}-{} has no event handlers registered. Not sending any subscriptions.", clientName, clientId);
		}
	}

	/*
	 * Incoming message processing
	 */

	@Override
	@Loggable(value = Loggable.TRACE, prepend = true)
	public void handleIncomingMessage(ByteBuffer buffer)
	{
		messageProcessorExecutor.submit(() -> processMessage(buffer));
	}

	@Loggable(value = Loggable.TRACE, prepend = true)
	private void processMessage(ByteBuffer messagePayload)
	{
		try
		{
			val metadata = MessageSerialiser.extractMetadata(messagePayload);
			val deserialised = MessageSerialiser.deserialiseEvent(messagePayload);
			val id = metadata.getMiddle();
			val type = metadata.getRight();

			if (type == MessageType.EVENT)
			{
				sentAndReceived.replace(id, true);
			}

			log.debug("Received {} as {} : {}", id, type, deserialised);
			val json = MessageSerialiser.extractEvent(messagePayload);
			log.trace("Payload for {} : {}", id, json);
			switch (type)
			{
				case EVENT ->
				{
					counters.eventReceived(deserialised);
					eventDispatcher.dispatch(deserialised);
				}
				case QUERY ->
				{
					counters.queryReceived(deserialised);
					eventDispatcher.dispatch(deserialised)
							.thenAccept(response ->
							{
								log.debug("Sending query response {} with ID {}", response, id);
								sendMessage(response, id, MessageType.RESPONSE);
							});
				}
				case RESPONSE ->
				{
					val responseType = deserialised.getClass().getName();
					if (messageHandlers.containsKey(responseType))
					{
						messageHandlers.get(responseType)
								.accept(id, deserialised);
						break;
					}
					val pair = awaitingResponse.remove(id);
					if (pair == null)
					{
						log.error("Missing future for query {}", id);
						break;
					}
					val start = pair.getLeft();
					val future = pair.getRight();
					val end = Instant.now();
					queryTimer.record(Duration.between(start, end));
					future.complete(deserialised);
				}
			}
		}
		catch (JacksonException | ClassCastException | InvalidMessage e)
		{
			log.error("Error deserialising or consuming event", e);
		}
	}

	@Loggable(value = Loggable.TRACE, prepend = true)
	private void handleServerAck(ServerAck serverAck)
	{
		this.serverId = serverAck.serverId();
	}

	@Loggable(value = Loggable.TRACE, prepend = true)
	private void handleClientAck(UUID id, ClientAck clientAck)
	{
		log.debug("Event {} handled", clientAck);
		val future = awaitingAck.remove(id);
		if (future == null)
		{
			if (!awaitingResponse.containsKey(id))
			{
				log.warn("Could not find future for ACK {} : {}", id, clientAck);
			}
			return;
		}
		future.complete(null);
	}

	@Loggable(value = Loggable.TRACE, prepend = true)
	private void handleHandlerNotFound(UUID id, HandlerNotFound handlerNotFound)
	{
		log.warn("No handler found for {}", handlerNotFound.getType());
		val isQuery = awaitingResponse.containsKey(id);
		CompletableFuture<Object> queryFuture = null;
		if (isQuery)
		{
			val pair = awaitingResponse.remove(id);
			queryFuture = pair.getRight();
		}
		val future = ObjectUtils.firstNonNull(awaitingAck.remove(id), queryFuture);
		var type = MessageType.EVENT;
		if (isQuery)
		{
			type = MessageType.QUERY;
			counters.queryUnhandled(handlerNotFound.getType());
		}
		else
		{
			counters.eventUnhandled(handlerNotFound.getType());
		}
		if (future == null)
		{
			log.error("Could not find future for {} {} : {}", type, id, handlerNotFound.toString());
			return;
		}
		future.completeExceptionally(handlerNotFound);
	}

	@Loggable(value = Loggable.TRACE, prepend = true)
	private void handleQueryException(UUID id, QueryException queryException)
	{
		val pair = awaitingResponse.remove(id);
		val future = pair.getRight();
		val queryType = queryException.getQueryType();
		val cause = queryException.getOriginalCause();
		counters.queryExceptional(queryType, cause);
		if (future == null)
		{
			log.error("Could not find future for QUERY {} : {}", id, queryException.toString());
			return;
		}
		val message = queryException.getOriginalMessage();
		val originalException = ExceptionReplicator.createException(cause, message);
		log.debug("Responding exceptionally to {} due to {} : {}", queryType, cause, message);
		future.completeExceptionally(originalException);
	}

	/*
	 * Writing (with backpressure)
	 */

	/**
	 * Sends any message object to the server. This method is thread-safe.
	 *
	 * @param message The message object to send.
	 */
	@Override
	@Loggable(value = Loggable.TRACE, prepend = true)
	public CompletableFuture<Void> sendMessage(Object message)
	{
		val id = UUID.randomUUID();
		val future = new CompletableFuture<Void>();
		awaitingAck.put(id, future);
		sendMessage(message, id, MessageType.EVENT);
		return future;
	}

	/**
	 * Sends any message object to the server. This method is thread-safe.
	 *
	 * @param message The message object to send.
	 */
	@SuppressWarnings("unchecked")
	@Override
	@Loggable(value = Loggable.TRACE, prepend = true)
	public <R> CompletableFuture<R> sendMessageWantResponse(Object message)
	{
		val queryId = UUID.randomUUID();
		val future = new CompletableFuture<R>();
		val now = Instant.now();
		val pair = Pair.of(now, (CompletableFuture<Object>)future);
		awaitingResponse.put(queryId, pair);
		sendMessage(message, queryId, MessageType.QUERY);
		return future;
	}

	@Loggable(value = Loggable.TRACE, prepend = true)
	private void sendMessage(Object message, UUID messageId, MessageType messageType)
	{
		val type = message.getClass().getName();
		if (!isConnected())
		{
			log.warn("Not connected; dropping message of type {}", type);
			return;
		}
		try
		{
			val serialisedMessage = MessageSerialiser.serialiseEvent(message, messageId, messageType);
			log.debug("Sending {} as {} : {}", messageId, messageType, message);
			val json = MessageSerialiser.extractEvent(serialisedMessage);
			log.trace("Payload for {} : {}", messageId, json);
			switch (messageType)
			{
				case EVENT:
					counters.eventSent(message);
					break;
				case QUERY:
					counters.querySent(message);
					break;
			}

			if (messageType == MessageType.EVENT)
				sentAndReceived.put(messageId, false);

			sendMessage(serialisedMessage);
		}
		catch (JacksonException | InvalidMessage e)
		{
			log.warn("Could not serialise event", e);
		}
	}
}
