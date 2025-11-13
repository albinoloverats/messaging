package net.albinoloverats.messaging.client.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import lombok.val;

import java.util.UUID;

/**
 * Implementation of common metrics counters
 */
final public class Counters extends net.albinoloverats.messaging.common.metrics.Counters
{
	private final String service;

	/**
	 * Constructor.
	 *
	 * @param id            Client ID.
	 * @param meterRegistry Meter registry.
	 * @param service       Client service name.
	 */
	public Counters(UUID id, MeterRegistry meterRegistry, String service)
	{
		super(id, meterRegistry);
		this.service = service;
	}

	/**
	 * Default, client specific, tags.
	 *
	 * @return Client tags.
	 */
	@Override
	public Tags defaultTags()
	{
		val role = Tag.of("role", "client");
		val instance = Tag.of("id", id.toString());
		val group = Tag.of("service", service);
		return Tags.of(role, instance, group);
	}
}
