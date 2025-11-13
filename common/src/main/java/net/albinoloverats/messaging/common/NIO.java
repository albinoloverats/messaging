package net.albinoloverats.messaging.common;


import com.jcabi.aspects.Loggable;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import net.albinoloverats.messaging.common.functions.QuietRunnable;
import net.albinoloverats.messaging.common.metrics.Counters;
import net.albinoloverats.messaging.common.metrics.RateLogger;
import net.albinoloverats.messaging.common.security.TLSChannel;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.AbstractSelectableChannel;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static net.albinoloverats.messaging.common.utils.Constants.AUTO_DISCOVERY;
import static net.albinoloverats.messaging.common.utils.Constants.PORT;

/**
 * Common code for messaging (Java NIO) server or client.
 *
 * @param <C> Either {@link ServerSocketChannel} or {@link SocketChannel} depending on whether the implementation is server or client.
 */
@Slf4j
public abstract class NIO<C extends AbstractSelectableChannel>
{
	protected final Map<UUID, Boolean> sentAndReceived = new ConcurrentHashMap<>();

	protected final ExecutorService messageProcessorExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
	private final ExecutorService selectorExecutor = Executors.newSingleThreadExecutor();
	protected final ScheduledExecutorService scheduledExecutor = Executors.newSingleThreadScheduledExecutor();

	protected volatile boolean running = false;
	@Getter
	protected final Selector selector;
	protected C channel;
	protected final SSLContext sslContext;

	protected final boolean autoDiscover;
	protected final Set<String> knownHosts = ConcurrentHashMap.newKeySet();
	protected DatagramChannel discoveryChannel;

	/*
	 * Metrics.
	 */
	@Getter
	protected final Counters counters;
	private final RateLogger rateLogger;

	protected UUID serverId;

	/**
	 * Default constructor.
	 *
	 * @param configuredHosts Known hosts to connect to.
	 * @param sslContext      The SSL context to use.
	 * @param counters        Metrics counters.
	 * @throws IOException Thrown if the socket selector cannot be opened.
	 */
	protected NIO(Set<String> configuredHosts, SSLContext sslContext, Counters counters) throws IOException
	{
		selector = Selector.open();
		this.sslContext = sslContext;
		this.counters = counters;
		rateLogger = new RateLogger(counters);
		autoDiscover = configuredHosts.size() == 1 && configuredHosts.contains(AUTO_DISCOVERY);
		if (autoDiscover)
		{
			configuredHosts.clear();
			enableAutoDiscovery();
		}
		knownHosts.addAll(configuredHosts);
		additionalMetrics(counters.getMeterRegistry());
	}

	/**
	 * Server constructor.
	 *
	 * @param configuredHosts Known peers to connect to.
	 * @param sslContext      The SSL context to use.
	 * @param counters        Metrics counters.
	 * @param channel         The server socket channel.
	 * @throws IOException Thrown if the channel cannot be configured correctly.
	 */
	protected NIO(Set<String> configuredHosts, SSLContext sslContext, Counters counters, C channel) throws IOException
	{
		this(configuredHosts, sslContext, counters);
		this.channel = channel;
		channel.configureBlocking(false);
	}

	/**
	 * Any additional metrics to register/collect.
	 *
	 * @param meterRegistry The meter registry.
	 */
	abstract protected void additionalMetrics(MeterRegistry meterRegistry);

	/**
	 * Method to run when the SSL handshake is complete.
	 *
	 * @param channel The socket channel.
	 */
	abstract protected void onHandshakeComplete(SocketChannel channel);

	/**
	 * Method to run when the selection key is acceptable.
	 *
	 * @param key The selection key.
	 */
	abstract protected void onKeyAcceptable(SelectionKey key) throws IOException;

	/**
	 * Method to run when the selection key is connectable.
	 *
	 * @param channel The socket channel.
	 */
	abstract protected void onKeyConnectable(SocketChannel channel) throws IOException;

	/**
	 * Method to run when the selection key is readable; data incoming.
	 *
	 * @param key The selection key.
	 */
	abstract protected void onKeyReadable(SelectionKey key) throws IOException;

	/**
	 * Method to run when the selection key is writable; data outgoing.
	 *
	 * @param key The selection key.
	 */
	abstract protected void onKeyWriteable(SelectionKey key) throws IOException;

	/**
	 * Method to run when on disconnect.
	 *
	 * @param attachment The attachment object associated with the selection key.
	 */
	abstract protected void onDisconnect(Object attachment);

	/**
	 * Process incoming auto-discovery message.
	 *
	 * @param datagramChannel The auto-discovery channel.
	 * @throws IOException Thrown if there is an error reading the incoming data.
	 */
	abstract protected void handleDiscoveryMessage(DatagramChannel datagramChannel) throws IOException;

	private void enableAutoDiscovery() throws IOException
	{
		discoveryChannel = DatagramChannel.open(StandardProtocolFamily.INET);
		discoveryChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
		discoveryChannel.setOption(StandardSocketOptions.SO_REUSEPORT, true);
		discoveryChannel.setOption(StandardSocketOptions.SO_BROADCAST, true);
		discoveryChannel.bind(new InetSocketAddress(PORT));
		discoveryChannel.configureBlocking(false);
		discoveryChannel.register(selector, SelectionKey.OP_READ);
	}

	/**
	 * Start the main run loop and rate-logger.
	 *
	 * @throws IOException Thrown by the client implementation if a connection cannot be made.
	 */
	protected void start() throws IOException
	{
		running = true;
		selectorExecutor.submit(this::run);
		rateLogger.start();
	}

	/**
	 * Main run loop.
	 */
	protected void run()
	{
		while (running)
		{
			Object attachment = null;
			try
			{
				selector.select();
				val selectedKeys = selector.selectedKeys();
				val iterator = selectedKeys.iterator();
				while (iterator.hasNext())
				{
					val key = iterator.next();
					val channel = key.channel();
					attachment = key.attachment();
					iterator.remove();

					if (!key.isValid())
					{
						continue;
					}
					else if (key.isConnectable())
					{
						val socketChannel = (SocketChannel)channel;
						onKeyConnectable(socketChannel);
					}
					else if (key.isAcceptable())
					{
						onKeyAcceptable(key);
					}
					else if (channel instanceof DatagramChannel datagramChannel && key.isReadable())
					{
						handleDiscoveryMessage(datagramChannel);
					}
					else if (key.isReadable() || key.isWritable())
					{
						val client = (NIOClient)attachment;
						if (client != null && !client.isHandshakeComplete())
						{
							client.handshake();
						}
						else if (key.isReadable())
						{
							onKeyReadable(key);
						}
						else if (key.isWritable())
						{
							onKeyWriteable(key);
						}
					}
				}
			}
			catch (IOException e)
			{
				log.error("Selector loop error", e);
				onDisconnect(attachment);
			}
		}
	}

	/**
	 * Create a client TLS channel.
	 *
	 * @return A new TLS channel.
	 * @throws SSLException Thrown if the channel could not be created.
	 */
	@Loggable(value = Loggable.TRACE, prepend = true)
	protected TLSChannel createTlsChannel() throws SSLException
	{
		return createTlsChannel((SocketChannel)channel, true);
	}

	/**
	 * Create a server TLS channel.
	 *
	 * @param socketChannel The client socket channel.
	 * @return A new TLS channel.
	 * @throws SSLException Thrown if the channel could not be created.
	 */
	@Loggable(value = Loggable.TRACE, prepend = true)
	protected TLSChannel createTlsChannel(SocketChannel socketChannel) throws SSLException
	{
		return createTlsChannel(socketChannel, false);
	}

	/**
	 * Create a TLS channel.
	 *
	 * @param socketChannel The socket channel.
	 * @param isClient      Whether the TLS channel should be created in client mode.
	 * @return A new TLS channel.
	 * @throws SSLException Thrown if the channel could not be created.
	 */
	@Loggable(value = Loggable.TRACE, prepend = true)
	protected TLSChannel createTlsChannel(SocketChannel socketChannel, boolean isClient) throws SSLException
	{
		val sslEngine = sslContext.createSSLEngine();
		sslEngine.setUseClientMode(isClient);
		return new TLSChannel(socketChannel, selector, sslEngine, this::onHandshakeComplete);
	}

	/**
	 * Stop the main run loop; closes the channel and stops the rate logger.
	 */
	@Loggable(value = Loggable.TRACE, prepend = true)
	public void stop()
	{
		running = false;
		Optional.ofNullable(channel)
				.ifPresent(c -> QuietRunnable.doQuietly(c::close));
		Optional.ofNullable(rateLogger)
				.ifPresent(r -> QuietRunnable.doQuietly(r::stop));
	}

	/**
	 * Shutdown the connection, closing the selector and auto-discovery channel if necessary.
	 */
	@Loggable(value = Loggable.TRACE, prepend = true)
	public void shutdown()
	{
		QuietRunnable.doQuietly(selector::close);
		selectorExecutor.shutdownNow();
		Optional.ofNullable(discoveryChannel)
				.ifPresent(c -> QuietRunnable.doQuietly(c::close));
	}
}
