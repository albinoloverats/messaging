package net.albinoloverats.messaging.common.utils;

import lombok.SneakyThrows;
import lombok.val;
import net.albinoloverats.messaging.common.TestEvent;
import net.albinoloverats.messaging.common.messages.MessageType;
import net.albinoloverats.messaging.common.messages.SubscribeToEvents;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SerialisationTests
{
	private static final TestEvent EVENT = new TestEvent(UUID.randomUUID(),
			"Lorum ipsum...",
			4,
			List.of(true, false),
			Map.of("A", "Z", "B", "Y"),
			Instant.EPOCH,
			Optional.of("maybe"),
			Optional.of(new TestEvent(UUID.randomUUID(),
					"Lorum ipsum...",
					7,
					List.of(),
					Map.of(),
					Instant.now(),
					Optional.of("maybe more"),
					Optional.empty())));
	private static final UUID MESSAGE_ID = UUID.randomUUID();

	@BeforeAll
	static void init()
	{
		val basePackages = new HashSet<String>();
		basePackages.add(TestEvent.class.getPackageName());
		basePackages.add(SubscribeToEvents.class.getPackageName());
		basePackages.add(Set.class.getPackageName());
		basePackages.add("java.util"); // for Set, UUID, maybe more...
		basePackages.add("java.time"); // for Instant and others...
		MessageSerialiser.initialise(basePackages);
	}

	@Test
	@SneakyThrows
	void verify_serialise_then_deserialise()
	{
		val buffer = MessageSerialiser.serialiseEvent(EVENT, MESSAGE_ID, MessageType.EVENT);

		assertNotNull(buffer);
		val length = buffer.getInt();
		assertTrue(length > 0);
		buffer.rewind();

		TestEvent result = MessageSerialiser.deserialiseEvent(buffer);
		assertNotNull(result);
		assertEquals(EVENT, result);
	}
}
