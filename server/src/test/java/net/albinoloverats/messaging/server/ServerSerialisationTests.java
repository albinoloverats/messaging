package net.albinoloverats.messaging.server;

import lombok.SneakyThrows;
import lombok.val;
import net.albinoloverats.messaging.common.TestEvent;
import net.albinoloverats.messaging.common.messages.MessageType;
import net.albinoloverats.messaging.common.messages.SubscribeToEvents;
import net.albinoloverats.messaging.common.utils.MessageSerialiser;
import net.albinoloverats.messaging.server.messages.PeerRelay;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class ServerSerialisationTests
{
	private static final UUID ID = UUID.fromString("5f5e9426-74f2-4675-b422-be6c3870cab4");
	private static final TestEvent EVENT = new TestEvent(ID,
			"Lorum ipsum...",
			4,
			List.of(true, false),
			Map.of("A", "Z", "B", "Y"),
			Instant.EPOCH,
			Optional.of("maybe"),
			Optional.empty());

	@BeforeAll
	static void init()
	{
		val basePackages = new HashSet<String>();
		basePackages.add(TestEvent.class.getPackageName());
		basePackages.add(SubscribeToEvents.class.getPackageName());
		basePackages.add(Set.class.getPackageName());
		basePackages.add(PeerRelay.class.getPackageName());
		basePackages.add("java.util"); // for Set, UUID, maybe more...
		basePackages.add("java.time"); // for Instant and others...
		MessageSerialiser.initialise(basePackages);
	}

	@Test
	@SneakyThrows
	void verify_broadcast_lifecycle()
	{
		var event = MessageSerialiser.serialiseEvent(EVENT, ID, MessageType.EVENT);
		val broadcast = new PeerRelay(event.array());
		val id = broadcast.id();
		val serialised = MessageSerialiser.serialiseEvent(broadcast, id, MessageType.EVENT);

		PeerRelay deserialised = MessageSerialiser.deserialiseEvent(serialised);
		assertEquals(id, deserialised.id());

		val payload = deserialised.event();
		TestEvent recovered = MessageSerialiser.deserialiseEvent(ByteBuffer.wrap(payload));
		assertEquals(EVENT, recovered);
	}
}
