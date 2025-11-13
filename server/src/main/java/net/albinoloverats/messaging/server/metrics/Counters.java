package net.albinoloverats.messaging.server.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import lombok.val;

import java.util.UUID;

final public class Counters extends net.albinoloverats.messaging.common.metrics.Counters
{
	public Counters(UUID id, MeterRegistry meterRegistry)
	{
		super(id, meterRegistry);
	}

	@Override
	public Tags defaultTags()
	{
		val role = Tag.of("role", "server");
		val instance = Tag.of("id", id.toString());
		return Tags.of(role, instance);
	}
}
