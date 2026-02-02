package net.albinoloverats.messaging.test.client;

import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import net.albinoloverats.messaging.client.client.MessagingClient;
import net.albinoloverats.messaging.client.config.AnnotatedEventDispatcher;
import net.albinoloverats.messaging.test.matchers.Matcher;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

@RequiredArgsConstructor
@Slf4j
public final class TestMessagingClient implements MessagingClient
{
	private final AnnotatedEventDispatcher eventDispatcher;

	private final List<Object> publishedEvents = new CopyOnWriteArrayList<>();
	private final List<Object> raisedQueries = new CopyOnWriteArrayList<>();
	private final List<QueryStub<?, ?>> queryStubs = new CopyOnWriteArrayList<>();

	@Setter
	private boolean withDispatch = false;

	/*
	 * Application API
	 */

	@Override
	public CompletableFuture<Void> sendMessage(Object message)
	{
		publishedEvents.add(message);
		if (withDispatch)
		{
			eventDispatcher.dispatch(message);
		}
		return CompletableFuture.completedFuture(null);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <R> CompletableFuture<R> sendMessageWantResponse(Object message)
	{
		raisedQueries.add(message);
		if (withDispatch)
		{
			return (CompletableFuture<R>)eventDispatcher.dispatch(message);
		}
		for (val stub : queryStubs)
		{
			val typed = (QueryStub<Object, R>)stub;
			if (typed.matcher().matches(message))
			{
				return CompletableFuture.completedFuture(typed.responder().apply(message));
			}
		}
		return CompletableFuture.failedFuture(new IllegalStateException("No stubbed response for query " + message));
	}

	/*
	 * Test API
	 */

	public void publishEvent(Object event)
	{
		eventDispatcher.dispatch(event);
	}

	@SuppressWarnings("unchecked")
	public <R> CompletableFuture<R> raiseQuery(Object query)
	{
		return (CompletableFuture<R>)eventDispatcher.dispatch(query);
	}

	@SuppressWarnings("unchecked")
	public boolean wasEventPublished(Object event)
	{
		if (event instanceof Matcher<?> matcher)
		{
			return publishedEvents.stream().anyMatch(published ->
					((Matcher<Object>)matcher).matches(published));
		}
		return publishedEvents.contains(event);
	}

	@SuppressWarnings("unchecked")
	public boolean wasQueryRaised(Object query)
	{
		if (query instanceof Matcher<?> matcher)
		{
			return raisedQueries.stream().anyMatch(sent ->
					((Matcher<Object>)matcher).matches(sent));
		}
		return raisedQueries.contains(query);
	}

	public <Q, R> void respondWith(Matcher<Q> matcher, Function<Q, R> responder)
	{
		queryStubs.add(new QueryStub<>(matcher, responder));
	}

	public void reset()
	{
		withDispatch = false;
		publishedEvents.clear();
		raisedQueries.clear();
		queryStubs.clear();
	}
}
