package net.albinoloverats.messaging.common.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.val;

import java.util.UUID;

import static net.albinoloverats.messaging.common.metrics.Constants.EVENT;
import static net.albinoloverats.messaging.common.metrics.Constants.QUERY;
import static net.albinoloverats.messaging.common.utils.Constants.MESSAGING;

/**
 * Common counter metric bits and pieces.
 */
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class Counters
{
	private static final String CAUSE = "cause";

	private static final String SENT = "sent";
	private static final String RECEIVED = "received";
	private static final String REPLIED = "replied";
	private static final String EXCEPTIONAL = "exceptional";
	private static final String UNHANDLED = "unhandled";

	static final String EVENT_SENT = MESSAGING + "." + EVENT + "." + SENT;
	static final String EVENT_RECEIVED = MESSAGING + "." + EVENT + "." + RECEIVED;
	static final String EVENT_UNHANDLED = MESSAGING + "." + EVENT + "." + UNHANDLED;
	static final String QUERY_SENT = MESSAGING + "." + QUERY + "." + SENT;
	static final String QUERY_RECEIVED = MESSAGING + "." + QUERY + "." + RECEIVED;
	static final String QUERY_REPLIED = MESSAGING + "." + QUERY + "." + REPLIED;
	static final String QUERY_EXCEPTIONAL = MESSAGING + "." + QUERY + "." + EXCEPTIONAL;
	static final String QUERY_UNHANDLED = MESSAGING + "." + QUERY + "." + UNHANDLED;

	protected final UUID id;
	@Getter
	private final MeterRegistry meterRegistry;

	/*
	 * Events sent.
	 */

	public void eventSent(Object event)
	{
		increment(EVENT_SENT, Tag.of(EVENT, event.getClass().getName()));
	}

	public void eventSent(String event)
	{
		increment(EVENT_SENT, Tag.of(EVENT, event));
	}

	public long eventSent()
	{
		val counter = meterRegistry.find(EVENT_SENT).counter();
		return counter != null ? (long)counter.count() : 0L;
	}

	/*
	 * Events received.
	 */

	public void eventReceived(Object event)
	{
		increment(EVENT_RECEIVED, Tag.of(EVENT, event.getClass().getName()));
	}

	public void eventReceived(String event)
	{
		increment(EVENT_RECEIVED, Tag.of(EVENT, event));
	}

	public long eventReceived()
	{
		val counter = meterRegistry.find(EVENT_RECEIVED).counter();
		return counter != null ? (long)counter.count() : 0L;
	}

	/*
	 * Events unhandled.
	 */

	public void eventUnhandled(Object event)
	{
		increment(EVENT_UNHANDLED, Tag.of(EVENT, event.getClass().getName()));
	}

	public void eventUnhandled(String event)
	{
		increment(EVENT_UNHANDLED, Tag.of(EVENT, event));
	}

	public long eventUnhandled()
	{
		val counter = meterRegistry.find(EVENT_UNHANDLED).counter();
		return counter != null ? (long)counter.count() : 0L;
	}

	/*
	 * Queries sent.
	 */

	public void querySent(Object query)
	{
		increment(QUERY_SENT, Tag.of(QUERY, query.getClass().getName()));
	}

	public void querySent(String query)
	{
		increment(QUERY_SENT, Tag.of(QUERY, query));
	}

	public long querySent()
	{
		val counter = meterRegistry.find(QUERY_SENT).counter();
		return counter != null ? (long)counter.count() : 0L;
	}

	/*
	 * Queries received.
	 */

	public void queryReceived(Object query)
	{
		increment(QUERY_RECEIVED, Tag.of(QUERY, query.getClass().getName()));
	}

	public void queryReceived(String query)
	{
		increment(QUERY_RECEIVED, Tag.of(QUERY, query));
	}

	public long queryReceived()
	{
		val counter = meterRegistry.find(QUERY_RECEIVED).counter();
		return counter != null ? (long)counter.count() : 0L;
	}

	/*
	 * Queries replied.
	 */

	public void queryReplied(Object query)
	{
		increment(QUERY_REPLIED, Tag.of(QUERY, query.getClass().getName()));
	}

	public void queryReplied(String query)
	{
		increment(QUERY_REPLIED, Tag.of(QUERY, query));
	}

	public long queryReplied()
	{
		val counter = meterRegistry.find(QUERY_REPLIED).counter();
		return counter != null ? (long)counter.count() : 0L;
	}

	/*
	 * Queries replied exceptionally..
	 */

	public void queryExceptional(Object query, Throwable cause)
	{
		increment(QUERY_EXCEPTIONAL, Tags.of(Tag.of(QUERY, query.getClass().getName()), Tag.of(CAUSE, cause.getClass().getName())));
	}

	public void queryExceptional(String query, String cause)
	{
		increment(QUERY_EXCEPTIONAL, Tags.of(Tag.of(QUERY, query), Tag.of(CAUSE, cause)));
	}

	public long queryExceptional()
	{
		val counter = meterRegistry.find(QUERY_EXCEPTIONAL).counter();
		return counter != null ? (long)counter.count() : 0L;
	}


	/*
	 * Queries unhandled.
	 */

	public void queryUnhandled(Object query)
	{
		increment(QUERY_UNHANDLED, Tag.of(QUERY, query.getClass().getName()));
	}

	public void queryUnhandled(String query)
	{
		increment(QUERY_UNHANDLED, Tag.of(QUERY, query));
	}

	public long queryUnhandled()
	{
		val counter = meterRegistry.find(QUERY_UNHANDLED).counter();
		return counter != null ? (long)counter.count() : 0L;
	}


	/*
	 * Miscellaneous.
	 */

	public abstract Tags defaultTags();

	private void increment(String name, Tag typeTag)
	{
		increment(name, Tags.of(typeTag));
	}

	private void increment(String name, Tags extraTags)
	{
		val tags = defaultTags().and(extraTags);
		val counter = meterRegistry.counter(name, tags);
		counter.increment();
	}

}
