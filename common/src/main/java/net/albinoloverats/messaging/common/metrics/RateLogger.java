package net.albinoloverats.messaging.common.metrics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import net.albinoloverats.messaging.common.utils.Constants;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static net.albinoloverats.messaging.common.metrics.Counters.EVENT_RECEIVED;
import static net.albinoloverats.messaging.common.metrics.Counters.EVENT_SENT;
import static net.albinoloverats.messaging.common.metrics.Counters.QUERY_RECEIVED;
import static net.albinoloverats.messaging.common.metrics.Counters.QUERY_REPLIED;
import static net.albinoloverats.messaging.common.metrics.Counters.QUERY_SENT;

/**
 * Simple rate logging.
 */
@Slf4j
@RequiredArgsConstructor
public final class RateLogger
{
	private ScheduledExecutorService scheduler;
	private final Counters counters;
	private final AtomicReference<Instant> previousInstant = new AtomicReference<>();
	private final Map<String, Long> previousValues = new ConcurrentHashMap<>();

	/**
	 * Start the rate logging run loop.
	 */
	public void start()
	{
		log.debug("Starting rate logger");

		previousInstant.set(Instant.now());

		scheduler = Executors.newSingleThreadScheduledExecutor();
		scheduler.scheduleAtFixedRate(() ->
		{
			val now = Instant.now();
			val duration = Duration.between(previousInstant.get(), now);

			val eventRates = calculateEventRates(duration);
			val queryRates = calculateQueryRates(duration);

			val rateEventReceived = eventRates.getLeft();
			val rateEventSent = eventRates.getRight();

			val rateQueryReceived = queryRates.getLeft();
			val rateQuerySent = queryRates.getMiddle();
			val rateQueryResponded = queryRates.getRight();

			log.info("Up {} ({} e/s) : {} ({} q/s) :: Down {} ({} e/s) : {} ({} q/s) :: Replied {} ({} q/s)",
					rateEventSent.count(), rateEventSent.rate(),
					rateQuerySent.count(), rateQuerySent.rate(),
					rateEventReceived.count(), rateEventReceived.rate(),
					rateQueryReceived.count(), rateQueryReceived.rate(),
					rateQueryResponded.count(), rateQueryResponded.rate());

			previousInstant.set(now);

		}, Constants.ONE_MINUTE.getSeconds(), Constants.ONE_MINUTE.getSeconds(), TimeUnit.SECONDS);
	}

	private Pair<Rate, Rate> calculateEventRates(Duration duration)
	{
		val previousReceived = previousValues.getOrDefault(EVENT_RECEIVED, 0L);
		val previousSent = previousValues.getOrDefault(EVENT_SENT, 0L);

		val received = counters.eventReceived();
		val sent = counters.eventSent();

		val rateReceived = calculateRate(duration, received - previousReceived);
		val rateSent = calculateRate(duration, sent - previousSent);

		previousValues.put(EVENT_RECEIVED, received);
		previousValues.put(EVENT_SENT, sent);

		return Pair.of(rateReceived, rateSent);
	}

	private Triple<Rate, Rate, Rate> calculateQueryRates(Duration duration)
	{
		val previousReceived = previousValues.getOrDefault(QUERY_RECEIVED, 0L);
		val previousSent = previousValues.getOrDefault(QUERY_SENT, 0L);
		val previousReplied = previousValues.getOrDefault(QUERY_REPLIED, 0L);

		val received = counters.queryReceived();
		val sent = counters.querySent();
		val replied = counters.queryReplied();

		val rateReceived = calculateRate(duration, received - previousReceived);
		val rateSent = calculateRate(duration, sent - previousSent);
		val rateResponded = calculateRate(duration, replied - previousReplied);

		previousValues.put(QUERY_RECEIVED, received);
		previousValues.put(QUERY_SENT, sent);
		previousValues.put(QUERY_REPLIED, replied);

		return Triple.of(rateReceived, rateSent, rateResponded);
	}

	private static Rate calculateRate(Duration duration, long diff)
	{
		val seconds = duration.toMillis() / 1000.0;
		val rate = (long)(diff / seconds);
		return new Rate(diff, rate);
	}

	/**
	 * Stop rate logging.
	 */
	public void stop()
	{
		if (scheduler != null)
		{
			scheduler.shutdownNow();
		}
	}

	private record Rate(long count, long rate)
	{
	}
}
