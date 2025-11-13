package net.albinoloverats.messaging.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.jcabi.aspects.Loggable;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import net.albinoloverats.messaging.common.NIO;
import net.albinoloverats.messaging.common.config.MessagingProperties;
import net.albinoloverats.messaging.common.exceptions.HandlerNotFound;
import net.albinoloverats.messaging.common.exceptions.InvalidMessage;
import net.albinoloverats.messaging.common.messages.ClientAck;
import net.albinoloverats.messaging.common.messages.MessageType;
import net.albinoloverats.messaging.common.messages.ServerAck;
import net.albinoloverats.messaging.common.messages.SubscribeToEvents;
import net.albinoloverats.messaging.common.utils.MessageSerialiser;
import net.albinoloverats.messaging.server.messages.PeerDisconnect;
import net.albinoloverats.messaging.server.messages.PeerRegistration;
import net.albinoloverats.messaging.server.messages.PeerRelay;
import net.albinoloverats.messaging.server.messages.PeerSubscriptions;
import net.albinoloverats.messaging.server.metrics.Counters;
import net.albinoloverats.messaging.server.utils.ConsistentHashRing;
import org.apache.commons.lang3.StringUtils;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static net.albinoloverats.messaging.common.metrics.Constants.CLIENTS;
import static net.albinoloverats.messaging.common.metrics.Constants.CONNECTIONS;
import static net.albinoloverats.messaging.common.metrics.Constants.CONNECTION_TYPE;
import static net.albinoloverats.messaging.common.metrics.Constants.PEERS;
import static net.albinoloverats.messaging.common.metrics.Constants.QUERY_PENDING;
import static net.albinoloverats.messaging.common.utils.Constants.DISCOVERY_BUFFER_SIZE;
import static net.albinoloverats.messaging.common.utils.Constants.FIVE_SECONDS;
import static net.albinoloverats.messaging.common.utils.Constants.NO_DISCOVERY;
import static net.albinoloverats.messaging.common.utils.Constants.ONE_SECOND;
import static net.albinoloverats.messaging.common.utils.Constants.PORT;
import static net.albinoloverats.messaging.common.utils.Constants.TEN_SECONDS;
import static net.albinoloverats.messaging.server.utils.QuietUtils.quietSleep;

@Slf4j
class MessagingServer extends NIO<ServerSocketChannel>
{
	private static final InetSocketAddress broadcastAddress = new InetSocketAddress("255.255.255.255", PORT);

	/*
	 * Client/peer management.
	 */
	private final ConsistentHashRing hashRing = new ConsistentHashRing();
	private final Set<ServerClient> clients = ConcurrentHashMap.newKeySet();
	private final Map<String, AtomicInteger> roundRobinCounters = new ConcurrentHashMap<>();

	/*
	 * Relayed events that have already been seen and processed. And periodic
	 * clean-up.
	 */
	private final Map<UUID, Instant> seenEvents = new ConcurrentHashMap<>();
	private ScheduledFuture<?> seenEventProcessor;

	private final Map<InetSocketAddress, PeerConnectionState> peerConnections = new ConcurrentHashMap<>();
	private ScheduledFuture<?> discoveryProcessor;
	private final boolean singleInstance;

	/*
	 * Map for message handling methods.
	 */
	private final Map<String, BiConsumer<ServerClient, Object>> messageHandlers = Map.ofEntries(
			Map.entry(PeerRegistration.class.getName(),
					(peer, message) -> handlePeerRegistration(peer, (PeerRegistration)message)),
			Map.entry(PeerDisconnect.class.getName(),
					(peer, message) -> handlePeerDisconnect(peer, (PeerDisconnect)message)),
			Map.entry(PeerRelay.class.getName(),
					(peer, message) -> handlePeerRelay(peer, (PeerRelay)message)),
			Map.entry(PeerSubscriptions.class.getName(),
					(peer, message) -> handlePeerSubscriptions(peer, (PeerSubscriptions)message)),
			Map.entry(SubscribeToEvents.class.getName(),
					(peer, message) -> handleSubscriptionRequest(peer, (SubscribeToEvents)message))
	);

	MessagingServer(MessagingProperties properties,
	                SSLContext sslContext,
	                MeterRegistry meterRegistry)
			throws IOException
	{
		val id = UUID.randomUUID();
		val counters = new Counters(id, meterRegistry);
		val configuredHosts = properties.hosts()
				.stream()
				.map(StringUtils::toRootLowerCase)
				.collect(Collectors.toSet());

		super(configuredHosts, sslContext, counters, ServerSocketChannel.open());

		serverId = id;

		singleInstance = configuredHosts.size() == 1 && configuredHosts.contains(NO_DISCOVERY);

		val socketAddress = new InetSocketAddress(PORT);
		channel.bind(socketAddress);
		channel.register(selector, SelectionKey.OP_ACCEPT);
	}

	protected void additionalMetrics(MeterRegistry meterRegistry)
	{
		val tags = counters.defaultTags();
		meterRegistry.gauge(CONNECTIONS, tags.and(Tag.of(CONNECTION_TYPE, PEERS)), clients, set ->
				set.stream()
						.filter(ServerClient::isPeer)
						.count());
		meterRegistry.gauge(CONNECTIONS, tags.and(Tag.of(CONNECTION_TYPE, CLIENTS)), clients, set ->
				set.stream()
						.filter(Predicate.not(ServerClient::isPeer))
						.count());
		meterRegistry.gauge(QUERY_PENDING, tags, clients, set ->
				set.stream()
						.filter(Predicate.not(ServerClient::isPeer))
						.map(ServerClient::getAwaitingResponse)
						.map(Set::size)
						.mapToInt(Integer::intValue)
						.sum());
	}

	@Override
	public void start() throws IOException
	{
		super.start();
		/*
		 * Find other server instances
		 */
		if (!singleInstance)
		{
			if (autoDiscover)
			{
				val buffer = ByteBuffer.wrap(serverId.toString().getBytes());
				discoveryProcessor = scheduledExecutor.scheduleWithFixedDelay(() ->
				{
					try
					{
						if (discoveryChannel != null)
						{
							discoveryChannel.send(buffer, broadcastAddress);
							buffer.rewind();
						}
					}
					catch (IOException e)
					{
						log.warn("Could not send broadcast discovery message", e);
					}
				}, ONE_SECOND.getSeconds(), ONE_SECOND.getSeconds(), TimeUnit.SECONDS);
			}
			else
			{
				discoveryProcessor = scheduledExecutor.scheduleWithFixedDelay(() ->
				{
					for (val peer : knownHosts)
					{
						InetSocketAddress address = null;
						try
						{
							address = new InetSocketAddress(peer, PORT);
							val state = peerConnections.putIfAbsent(address, PeerConnectionState.DISCONNECTED);
							if (state == PeerConnectionState.DISCONNECTED)
							{
								peerConnections.replace(address, PeerConnectionState.CONNECTING);
								connectToPeer(address);
							}
						}
						catch (Exception e)
						{
							log.warn("Failed to schedule connection to peer {}", peer, e);
							if (address != null)
							{
								peerConnections.replace(address, PeerConnectionState.DISCONNECTED);
							}
						}
					}
				}, TEN_SECONDS.getSeconds(), TEN_SECONDS.getSeconds(), TimeUnit.SECONDS);
			}
		}

		/*
		 * Remove old broadcast events
		 */
		seenEventProcessor = scheduledExecutor.scheduleAtFixedRate(() ->
		{
			val now = Instant.now();
			seenEvents.entrySet()
					.removeIf(entry ->
							entry.getValue()
									.plus(TEN_SECONDS)
									.isBefore(now));
		}, TEN_SECONDS.getSeconds(), TEN_SECONDS.getSeconds(), TimeUnit.SECONDS);
	}

	/*
	 * Incoming client connection handling
	 */

	@Override
	@Loggable(value = Loggable.TRACE, prepend = true)
	protected void onHandshakeComplete(SocketChannel channel)
	{
		clients.stream()
				.filter(client -> client.hasChannel(channel))
				.findFirst()
				.ifPresent(client ->
						log.info("Completed handshake with {}", client));
	}

	@Override
	@Loggable(value = Loggable.TRACE, prepend = true)
	protected void onKeyAcceptable(SelectionKey key) throws IOException
	{
		val clientChannel = channel.accept();
		if (clientChannel != null)
		{
			log.info("Accepted connection from {}", clientChannel.getRemoteAddress());
			clientChannel.configureBlocking(false);
			val tlsChannel = createTlsChannel(clientChannel);
			val client = new ServerClient(this, clientChannel, tlsChannel);
			clients.add(client);
			clientChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE, client);
			client.handshake();
		}
	}

	/*
	 * Peer connection handling
	 */

	@Loggable(value = Loggable.DEBUG, prepend = true)
	protected void handleDiscoveryMessage(DatagramChannel channel) throws IOException
	{
		val buffer = ByteBuffer.allocate(DISCOVERY_BUFFER_SIZE);
		val peerAddress = channel.receive(buffer);
		if (peerAddress instanceof InetSocketAddress address)
		{
			buffer.flip();
			val bytes = new byte[buffer.remaining()];
			buffer.get(bytes);
			val peerId = UUID.fromString(new String(bytes));
			if (!peerId.equals(serverId))
			{
				/*
				 * Ignore broadcast messages from ourselves
				 */
				val state = peerConnections.putIfAbsent(address, PeerConnectionState.DISCONNECTED);
				if (state == PeerConnectionState.DISCONNECTED)
				{
					peerConnections.put(address, PeerConnectionState.CONNECTING);
					log.info("Discovered new peer at {}; attempting to connect", address);
					connectToPeer(address);
				}
			}
		}
	}

	@Loggable(value = Loggable.TRACE, prepend = true)
	void connectToPeer(InetSocketAddress address)
	{
		try
		{
			val peerChannel = SocketChannel.open();
			peerChannel.configureBlocking(false);
			if (peerChannel.connect(address))
			{
				val tlsChannel = createTlsChannel(peerChannel);
				val client = new ServerClient(this, peerChannel, tlsChannel);
				clients.add(client);
				peerChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE, client);
				client.handshake();
				log.info("Successfully connected to {}", address);
			}
			else
			{
				/*
				 * Register for OP_CONNECT to complete the connection later.
				 */
				peerChannel.register(selector, SelectionKey.OP_CONNECT);
			}
		}
		catch (IOException e)
		{
			log.warn("Could not connect to peer at {}", address, e);
			peerConnections.put(address, PeerConnectionState.DISCONNECTED);
		}
	}

	@Override
	@Loggable(value = Loggable.TRACE, prepend = true)
	protected void onKeyConnectable(SocketChannel channel) throws IOException
	{
		InetSocketAddress address = null;
		try
		{
			address = (InetSocketAddress)channel.getRemoteAddress();
			if (channel.isConnectionPending())
			{
				if (channel.finishConnect())
				{
					peerConnections.put(address, PeerConnectionState.CONNECTED);
					val tlsChannel = createTlsChannel(channel, true);
					val client = new ServerClient(this, channel, tlsChannel);
					clients.add(client);
					channel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE, client);
					client.handshake();
				}
			}
		}
		catch (IOException e)
		{
			peerConnections.put(address, PeerConnectionState.DISCONNECTED);
			throw e;
		}
	}

	/*
	 * General IO
	 */

	@Override
	@Loggable(value = Loggable.TRACE, prepend = true)
	protected void onKeyReadable(SelectionKey key) throws IOException
	{
		val handler = (ServerClient)key.attachment();
		handler.handleRead();
	}

	@Override
	@Loggable(value = Loggable.TRACE, prepend = true)
	protected void onKeyWriteable(SelectionKey key) throws IOException
	{
		val handler = (ServerClient)key.attachment();
		handler.handleWrite();
	}

	@Override
	public void stop()
	{
		running = false;
		sendDisconnect(Optional.empty());
		discoveryProcessor.cancel(true);
		seenEventProcessor.cancel(true);
		scheduledExecutor.shutdownNow();
		messageProcessorExecutor.shutdownNow();
		/*
		 * Wait long enough for disconnect to be broadcast
		 */
		quietSleep(FIVE_SECONDS);
		scheduledExecutor.shutdownNow();
		clients.forEach(ServerClient::close);
		clients.clear();

		super.stop();
		log.info("MessagingServer stopped");
	}

	@Override
	@Loggable(value = Loggable.TRACE, prepend = true)
	protected void onDisconnect(Object attachment)
	{
		val client = (ServerClient)attachment;
		client.disconnect();
		clients.remove(client);
	}

	/*
	 * Incoming message handling
	 */

	@Loggable(value = Loggable.TRACE, prepend = true)
	void handleIncomingMessage(ServerClient client, ByteBuffer messagePayload, boolean relay)
	{
		messageProcessorExecutor.submit(() -> processMessage(client, messagePayload, relay));
	}

	/*
	 * This method is (should be) called in a separate thread pool
	 */
	@Loggable(value = Loggable.TRACE, prepend = true)
	private void processMessage(ServerClient client, ByteBuffer messagePayload, boolean relay)
	{
		try
		{
			val messageType = MessageSerialiser.findEventType(messagePayload);
			log.debug("Received {} from {}", messageType, client);
			messagePayload.rewind();
			val tasks = new HashSet<Runnable>();
			if (messageHandlers.containsKey(messageType))
			{
				val handler = messageHandlers.get(messageType);
				val triple = MessageSerialiser.separateMessage(messagePayload);
				val message = MessageSerialiser.deserialiseMessage(triple.getLeft());
				tasks.add(() -> handler.accept(client, message));
			}
			else
			{
				if (relay)
				{
					/*
					 * Check if a message can be handled.
					 */
					val triple = MessageSerialiser.separateMessage(messagePayload);
					messagePayload.rewind();
					val id = triple.getMiddle();
					val type = triple.getRight();

					if (type == MessageType.EVENT)
						sentAndReceived.put(id, true);

					if (type != MessageType.RESPONSE)
					{
						/*
						 * Query responses have already been handled.
						 */
						val canHandle = clients.stream()
								.anyMatch(peer ->
										peer.canHandleEvent(messageType) || peer.canHandleQuery(messageType));
						if (canHandle)
						{
							val ack = new ClientAck(messageType);
							val response = MessageSerialiser.serialiseEvent(ack, id, MessageType.RESPONSE);
							tasks.add(() -> client.sendMessage(response));
						}
						else
						{
							if (type == MessageType.QUERY)
							{
								counters.queryUnhandled(messageType);
							}
							else
							{
								counters.eventUnhandled(messageType);
							}
							val handlerNotFound = new HandlerNotFound(messageType);
							val response = MessageSerialiser.serialiseEvent(handlerNotFound, id, MessageType.RESPONSE);
							messageProcessorExecutor.submit(() -> client.sendMessage(response));
							throw handlerNotFound;
						}
					}
					tasks.add(() -> sendRelay(messagePayload.duplicate(), messageType));
				}
				tasks.add(() -> publishToSubscribers(client, messagePayload.duplicate(), messageType));
			}
			tasks.forEach(messageProcessorExecutor::submit);
		}
		catch (JsonProcessingException | InvalidMessage e)
		{
			log.error("Could not deserialise event and/or event ID", e);
		}
		catch (HandlerNotFound e)
		{
			log.warn("No handler found for {}", e.getType());
		}
	}

	/*
	 * Peer messaging
	 */

	@Loggable(value = Loggable.TRACE, prepend = true)
	private void sendPeerRegistration(ServerClient peer)
	{
		try
		{
			val registration = new PeerRegistration(serverId);
			val buffer = MessageSerialiser.serialiseEvent(registration, serverId, MessageType.EVENT);
			log.debug("Sending PeerRegistration to {}", peer);
			peer.sendMessage(buffer);
		}
		catch (JsonProcessingException e)
		{
			log.error("Could not serialise peer registration message", e);
		}
	}

	@Loggable(value = Loggable.TRACE, prepend = true)
	private void handlePeerRegistration(ServerClient peer, PeerRegistration peerRegistration)
	{
		val peerId = peerRegistration.peerId();
		if (peerId == null)
		{
			log.warn("Peer registration from {} missing peer ID; closing connection", peer);
			handlePeerDisconnect(peer, null);
			return;
		}
		if (peerId.equals(serverId))
		{
			log.warn("Received peer registration from ourselves; closing connection");
			handlePeerDisconnect(peer, null);
			return;
		}
		/*
		 * We were not previously aware this client was a peer, so it won't
		 * have received/handled a registration from us; set that up now.
		 */
		if (!peer.isPeer())
		{
			sendPeerRegistration(peer);
		}

		log.debug("{} now has ID {}", peer, peerId);
		peer.setClientId(peerId);
		peer.setPeer(true);

		updatePeerSubscriptions();
	}

	@Loggable(value = Loggable.TRACE, prepend = true)
	void sendDisconnect(Optional<ServerClient> peer)
	{
		val peers = peer.map(Set::of)
				.orElse(clients.stream()
						.filter(ServerClient::isPeer)
						.collect(Collectors.toSet()));
		if (peer.isEmpty())
		{
			log.debug("Broadcasting disconnect to {} peers", peers.size());
		}
		val disconnect = new PeerDisconnect(serverId, peer.isPresent());
		try
		{
			val buffer = MessageSerialiser.serialiseEvent(disconnect, serverId, MessageType.EVENT);
			peers.forEach(p -> p.sendMessage(buffer.duplicate()));
		}
		catch (JsonProcessingException e)
		{
			log.error("Could not serialise broadcast disconnect", e);
		}
	}

	@Loggable(value = Loggable.TRACE, prepend = true)
	private void handlePeerDisconnect(ServerClient peer, PeerDisconnect disconnect)
	{
		val peerId = peer.getClientId();
		if (disconnect != null && !peerId.equals(disconnect.peerId()))
		{
			log.warn("Peer disconnect ID mismatch {} ≠ {} from {}", peerId, disconnect.peerId(), peer);
		}
		val isDuplicate = disconnect != null && disconnect.isDeduplication();
		if (isDuplicate)
		{
			log.debug("Removing duplicate connection from {}", peer);
		}
		else
		{
			try
			{
				val address = (InetSocketAddress)peer.getChannel()
						.getRemoteAddress();
				peerConnections.put(address, PeerConnectionState.CONNECTED);
			}
			catch (IOException ignored)
			{
			}
		}
		clients.remove(peer);
		peer.close();
		if (!isDuplicate)
		{
			updatePeerSubscriptions();
			log.info("Peer {} has disconnected", peer);
		}
	}

	@Loggable(value = Loggable.TRACE, prepend = true)
	private void sendRelay(ByteBuffer buffer, String type)
	{
		PeerRelay relay = null;
		UUID messageId = null;
		MessageType messageType = null;
		try
		{
			val triple = MessageSerialiser.separateMessage(buffer);
			buffer.rewind();
			messageId = triple.getMiddle();
			messageType = triple.getRight();
			val peers = clients.stream()
					.filter(ServerClient::isPeer)
					.collect(Collectors.toSet());

			relay = new PeerRelay(buffer.duplicate().array());
			log.debug("Relaying {} message {} of {} {} to {} peers", type, relay.id(), messageType, messageId, peers.size());

			seenEvents.put(relay.id(), Instant.now());
			val relayBuffer = MessageSerialiser.serialiseEvent(relay, relay.id(), MessageType.EVENT);
			peers.forEach(peer -> peer.sendMessage(relayBuffer.duplicate()));
		}
		catch (JsonProcessingException | InvalidMessage e)
		{
			log.error("Could not serialise relay {} message {} of {} {}",
					type,
					relay != null ? relay.id() : "(null)",
					messageType != null ? messageType : "(null)",
					messageId != null ? messageId : "(null)",
					e);
		}
	}

	@Loggable(value = Loggable.TRACE, prepend = true)
	private void handlePeerRelay(ServerClient peer, PeerRelay relay)
	{
		if (seenEvents.containsKey(relay.id()))
		{
			return;
		}
		/*
		 * Incoming relay message, unwrap and forward to all clients
		 */
		seenEvents.put(relay.id(), Instant.now());
		val messagePayload = relay.event();
		val buffer = ByteBuffer.wrap(messagePayload);
		try
		{
			val triple = MessageSerialiser.separateMessage(buffer);
			val messageId = triple.getMiddle();
			val type = triple.getRight();
			buffer.rewind();
			log.debug("Received relay message {} for {} {}", relay.id(), type, messageId);
			handleIncomingMessage(peer, buffer, false);
		}
		catch (JsonProcessingException | InvalidMessage e)
		{
			log.error("Could not deserialise relay message: {}", relay.id(), e);
		}
	}

	@Loggable(value = Loggable.TRACE, prepend = true)
	private void updatePeerSubscriptions()
	{
		val peers = clients.stream()
				.parallel()
				.filter(ServerClient::isPeer)
				.collect(Collectors.toSet());
		if (peers.isEmpty())
		{
			return;
		}
		val eventSubscriptions = new HashSet<String>();
		val querySubscriptions = new HashSet<String>();
		val onlyClients = clients.stream()
				.filter(Predicate.not(ServerClient::isPeer))
				.toList();
		onlyClients.forEach(client ->
		{
			eventSubscriptions.addAll(client.getEventSubscriptions());
			querySubscriptions.addAll(client.getQuerySubscriptions());
		});
		try
		{
			log.debug("Updating {} peers with {} known subscriptions from {} clients", peers.size(), eventSubscriptions.size() + querySubscriptions.size(), onlyClients.size());
			val peerUpdate = new PeerSubscriptions(serverId, eventSubscriptions, querySubscriptions);
			val buffer = MessageSerialiser.serialiseEvent(peerUpdate, serverId, MessageType.EVENT);
			peers.forEach(peer -> peer.sendMessage(buffer.duplicate()));
		}
		catch (JsonProcessingException e)
		{
			log.error("Could not serialise subscription update", e);
		}
	}

	@Loggable(value = Loggable.TRACE, prepend = true)
	private void handlePeerSubscriptions(ServerClient peer, PeerSubscriptions subscriptions)
	{
		val eventTypes = ConcurrentHashMap.<String>newKeySet();
		eventTypes.addAll(subscriptions.eventSubscriptions());
		val queryTypes = ConcurrentHashMap.<String>newKeySet();
		queryTypes.addAll(subscriptions.querySubscriptions());

		peer.setEventSubscriptions(eventTypes);
		peer.setQuerySubscriptions(queryTypes);

		log.debug("Updated {} with {} known subscriptions", peer, eventTypes.size() + queryTypes.size());

		updateHashRing();
	}

	/*
	 * Client message handling
	 */

	@Loggable(value = Loggable.TRACE, prepend = true)
	private void handleDisconnect(ServerClient client)
	{
		if (client.isPeer())
		{
			handlePeerDisconnect(client, null);
			return;
		}
		clients.remove(client);
		client.close();
		updatePeerSubscriptions();
		log.info("{} has disconnected", client);
	}

	@Loggable(value = Loggable.TRACE, prepend = true)
	private void handleSubscriptionRequest(ServerClient client, SubscribeToEvents request)
	{
		val clientId = request.serviceId();
		val serviceName = request.serviceName();

		val eventTypes = ConcurrentHashMap.<String>newKeySet();
		eventTypes.addAll(request.subscribedEventTypes());

		val queryTypes = ConcurrentHashMap.<String>newKeySet();
		queryTypes.addAll(request.subscribedQueryTypes());

		if (clientId == null)
		{
			log.warn("Subscription request from {} missing client ID", client);
			return;
		}
		if (serviceName == null || serviceName.isBlank())
		{
			log.warn("Subscription request from {} missing service name", client);
			return;
		}
		client.setClientId(clientId);
		client.setServiceName(serviceName);

		val allTypes = new HashSet<>(eventTypes);
		allTypes.addAll(queryTypes);
		log.info("{} is subscribing to: {}", client, allTypes);

		client.setEventSubscriptions(eventTypes);
		client.setQuerySubscriptions(queryTypes);

		updateHashRing();
		updatePeerSubscriptions();
		roundRobinCounters.computeIfAbsent(serviceName, k -> new AtomicInteger(0));

		try
		{
			val ack = new ServerAck(serverId);
			val buffer = MessageSerialiser.serialiseEvent(ack, serverId, MessageType.RESPONSE);
			client.sendMessage(buffer);
		}
		catch (JsonProcessingException e)
		{
			log.error("Could not serialise ACK", e);
		}
	}

	@Loggable(value = Loggable.TRACE, prepend = true)
	private void publishToSubscribers(ServerClient client, ByteBuffer messagePayload, String messageType)
	{
		UUID messageId;
		MessageType type;
		try
		{
			val triple = MessageSerialiser.separateMessage(messagePayload);
			messagePayload.rewind();
			messageId = triple.getMiddle();
			type = triple.getRight();
		}
		catch (JsonProcessingException | InvalidMessage e)
		{
			log.error("Could not deserialise event and/or message ID", e);
			return;
		}
		val clients = this.clients.stream()
				.filter(Predicate.not(ServerClient::isPeer))
				.toList();
		val responded = clients.stream()
				.filter(c -> c.isWaitingFor(messageId))
				.findFirst()
				.map(waitingClient ->
				{
					log.debug("Responding to query {} with ID {} to {}", messageType, messageId, waitingClient);
					waitingClient.sendMessage(messagePayload);
					waitingClient.noLongerWaitingFor(messageId);
					counters.queryReplied(messageType);
					return true;
				})
				.orElse(false);
		if (responded)
		{
			return;
		}
		if (type == MessageType.QUERY)
		{
			counters.queryReceived(messageType);
			client.waitingFor(messageId);
		}
		else
		{
			counters.eventReceived(messageType);
		}
		val subscribers = clients.stream()
				.filter(c -> c.hasSubscribersFor(messageType))
				.collect(Collectors.toSet());
		log.debug("Sending {} with ID {} to (up to) {} subscribers", messageType, messageId, subscribers.size());
		subscribers.stream()
				.collect(Collectors.groupingBy(ServerClient::getServiceName))
				.forEach((service, candidates) ->
				{
					if (candidates.isEmpty())
					{
						return;
					}
					val responsibleServer = hashRing.getServerForKey(messageType, messageId, service);
					if (!serverId.equals(responsibleServer))
					{
						log.debug("Skipping {}, server {} is responsible", service, responsibleServer);
						return;
					}
					ServerClient target;
					if (candidates.size() == 1)
					{
						target = candidates.getFirst();
					}
					else
					{
						val counter = roundRobinCounters.computeIfAbsent(service, k -> new AtomicInteger(0));
						val index = Math.floorMod(counter.getAndIncrement(), candidates.size());
						target = candidates.get(index);
					}
					log.debug("Sending {} with ID {} to {}", messageType, messageId, target);
					target.sendMessage(messagePayload.duplicate());
					if (type == MessageType.EVENT)
					{
						counters.eventSent(messageType);
					}
					else
					{
						counters.querySent(messageType);
					}
				});
	}

	@Loggable(value = Loggable.TRACE, prepend = true)
	private void updateHashRing()
	{
		val allCapabilities = clients.stream()
				.flatMap(client ->
				{
					val serverId = client.isPeer() ? client.getClientId() : this.serverId;
					return Stream.concat(
									client.getEventSubscriptions().stream(),
									client.getQuerySubscriptions().stream())
							.map(type -> Map.entry(type, serverId));
				})
				.collect(Collectors.groupingBy(
						Map.Entry::getKey,
						Collectors.mapping(Map.Entry::getValue, Collectors.toSet())));
		hashRing.updateServers(allCapabilities);
	}
}
