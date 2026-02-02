package net.albinoloverats.messaging.client;

import com.jcabi.aspects.Loggable;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.albinoloverats.messaging.client.client.MessagingClient;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * A gateway for applications to publish events or queries to the MessagingServer.
 * This class abstracts the underlying messaging client.
 */
@Slf4j
@RequiredArgsConstructor
final public class MessagingGateway
{
	private final MessagingClient messagingClient;

	/**
	 * Publishes an event to the MessagingServer.
	 * The event object will be serialised and sent. No successful response is
	 * available from event publishing, but if an event handler cannot be found
	 * an exceptional future is returned.
	 *
	 * @param event The event object to publish.
	 * @return An exceptional future if a handled cannot be found.
	 */
	@Loggable(value = Loggable.TRACE, prepend = true)
	public CompletableFuture<Void> publish(@NonNull Object event)
	{
		log.debug("Publishing event: {}", event.getClass().getName());
		return messagingClient.sendMessage(event);
	}

	/*
	 * TODO Possibly allow for collections of events (how to handle exceptions?)
	 */

	/**
	 * Publishes a query to the MessagingServer. The query object will be
	 * serialised and sent, and a response should eventually be returned.
	 *
	 * @param query The query object to publish.
	 * @return The query response, eventually.
	 */
	@Loggable(value = Loggable.TRACE, prepend = true)
	public <R> CompletableFuture<R> query(@NonNull Object query)
	{
		log.debug("Publishing query: {}", query.getClass().getName());
		return messagingClient.sendMessageWantResponse(query);
	}

	/**
	 * Wrapper method to get the client ID.
	 *
	 * @return The client ID.
	 */
	public UUID getClientId()
	{
		return messagingClient.getClientId();
	}
}
