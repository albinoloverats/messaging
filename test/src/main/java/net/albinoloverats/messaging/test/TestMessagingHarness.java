package net.albinoloverats.messaging.test;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.val;
import net.albinoloverats.messaging.test.annotations.MessagingLiveClient;
import net.albinoloverats.messaging.test.client.TestMessagingClient;
import net.albinoloverats.messaging.test.matchers.Matcher;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;

import static net.albinoloverats.messaging.test.matchers.Matchers.exactly;

/**
 * Test harness for AMA Messaging Architecture. Allows for testing of
 * event/query producers separately from consumers, or combined as an
 * embedded server.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TestMessagingHarness
{
	@Setter
	private static volatile TestMessagingClient client;
	@Setter
	private static volatile boolean liveClient = false;

	/**
	 * Publishes the given event, for testing event handlers. Will work
	 * when using {@link MessagingLiveClient}, although it will only
	 * publish locally; ideal for kick-starting a test workflow.
	 *
	 * @param event The event to publish.
	 */
	public static void publishEvent(Object event)
	{
		client.publishEvent(event);
	}

	/**
	 * Raises the given query, for testing query handlers. Will work
	 * when using {@link MessagingLiveClient}, although it will only
	 * raise locally; ideal for kick-starting a test workflow.
	 *
	 * @param query The query to raise.
	 * @return The response from your query handler.
	 */
	public static <R> CompletableFuture<R> raiseQuery(Object query)
	{
		return client.raiseQuery(query);
	}

	/**
	 * Verify an event was published; for testing your event publishing
	 * methods. Not available when using {@link MessagingLiveClient}.
	 *
	 * @param event The expected event.
	 */
	public static void verifyEvent(Object event)
	{
		prerequisites();
		if (!client.wasEventPublished(event))
		{
			throw new AssertionError("Expected event was not published: " + event);
		}
	}

	/**
	 * Verify an event was published, using a Matcher for flexibility;
	 * for testing your event publishing methods. Not available when
	 * using {@link MessagingLiveClient}.
	 *
	 * @param matcher The expected event matcher.
	 */
	public static void verifyEvent(Matcher<Object> matcher)
	{
		prerequisites();
		if (!client.wasEventPublished(matcher))
		{
			throw new AssertionError("Expected event was not published: " + matcher);
		}
	}

	/**
	 * Verify events were published; for testing your event publishing
	 * methods. Not available when using {@link MessagingLiveClient}.
	 *
	 * @param events The expected events.
	 */
	public static void verifyEvents(Object... events)
	{
		prerequisites();
		val missing = Arrays.stream(events)
				.filter(Predicate.not(event -> client.wasEventPublished(event)))
				.toList();
		if (!missing.isEmpty())
		{
			throw new AssertionError("Expected events were not published: " + missing);
		}
	}

	/**
	 * Verify a query was raised; for testing your query raising methods.
	 * Not available when using {@link MessagingLiveClient}.
	 *
	 * @param query The expected query.
	 */
	public static void verifyQuery(Object query)
	{
		prerequisites();
		if (!client.wasQueryRaised(query))
		{
			throw new AssertionError("Expected query was not raised: " + query);
		}
	}

	/**
	 * Verify a query was raised, using a Matcher for flexibility; for
	 * testing your query raising methods. Not available when using
	 * {@link MessagingLiveClient}.
	 *
	 * @param matcher The expected query matcher.
	 */
	public static void verifyQuery(Matcher<Object> matcher)
	{
		prerequisites();
		if (!client.wasQueryRaised(matcher))
		{
			throw new AssertionError("Expected query was not raised: " + matcher);
		}
	}

	/**
	 * Verify queries were raised; for testing your query raising
	 * methods. Not available when using {@link MessagingLiveClient}.
	 *
	 * @param queries The expected queries.
	 */
	public static void verifyQueries(Object... queries)
	{
		prerequisites();
		val missing = Arrays.stream(queries)
				.filter(Predicate.not(query -> client.wasQueryRaised(query)))
				.toList();
		if (!missing.isEmpty())
		{
			throw new AssertionError("Expected queries were not raised: " + missing);
		}
	}

	/**
	 * Respond to a query, matched by the matcher, with the response from
	 * the responder. Not available when using {@link MessagingLiveClient}.
	 *
	 * @param query     The query to response to.
	 * @param responder How to respond to the query.
	 */
	@SuppressWarnings("unchecked")
	public static <Q, R> void withQueryHandler(Object query, Function<Q, R> responder)
	{
		prerequisites();
		withQueryHandler(exactly((Q)query), responder);
	}

	/**
	 * Respond to a query, matched by the matcher, with the response from
	 * the responder. Not available when using {@link MessagingLiveClient}.
	 *
	 * @param matcher   How to match the query.
	 * @param responder How to respond to the query.
	 */
	public static <Q, R> void withQueryHandler(Matcher<Q> matcher, Function<Q, R> responder)
	{
		prerequisites();
		client.respondWith(matcher, responder);
	}

	private static void prerequisites()
	{
		if (liveClient)
		{
			throw new IllegalStateException("TestMessagingHarness not available when using @MessagingLiveClient");
		}
	}
}
