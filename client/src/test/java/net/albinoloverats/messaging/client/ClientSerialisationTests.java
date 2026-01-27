package net.albinoloverats.messaging.client;

import lombok.SneakyThrows;
import lombok.val;
import net.albinoloverats.messaging.client.config.EventSerialisationConfig;
import net.albinoloverats.messaging.common.TestEvent;
import net.albinoloverats.messaging.common.messages.MessageType;
import net.albinoloverats.messaging.common.messages.SubscribeToEvents;
import net.albinoloverats.messaging.common.utils.MessageSerialiser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.skyscreamer.jsonassert.JSONCompareMode.NON_EXTENSIBLE;

@SpringBootTest(classes = EventSerialisationConfig.class)
class ClientSerialisationTests
{
	private static final String KNOWN_EVENT_JSON = """
			[
				"net.albinoloverats.messaging.common.TestEvent",
				{
					"id" : "5f5e9426-74f2-4675-b422-be6c3870cab4",
					"description": "Lorum ipsum...",
					"value" : 4,
					"yesNo" :
					[
						"java.util.ImmutableCollections$List12",
						[
							true,
							false
						]
					],
					"more" :
					[
						"java.util.ImmutableCollections$MapN",
						{
							"B" : "Y",
							"A" : "Z"
						}
					],
					"at" : "1970-01-01T00:00:00Z",
					"maybe" : "maybe",
					"another" : null
				},
				"9d252d74-4506-4769-b599-888a7c671a2b",
				"EVENT"
			]
			""";
	private static final String UNKNOWN_EVENT_JSON = """
			[
				"net.albinoloverats.messaging.other.Unknown",
				{
					"id" : "5f5e9426-74f2-4675-b422-be6c3870cab4",
					"description": "Lorum ipsum...",
					"value" : 4,
					"yesNo" :
					[
						"java.util.ImmutableCollections$List12",
						[
							true,
							false
						]
					],
					"more" :
					[
						"java.util.ImmutableCollections$MapN",
						{
							"B" : "Y",
							"A" : "Z"
						}
					],
					"at" : "1970-01-01T00:00:00Z",
					"maybe" : "maybe",
					"another" : null
				},
				"9d252d74-4506-4769-b599-888a7c671a2b",
				"EVENT"
			]
			""";
	private static final TestEvent EVENT = new TestEvent(UUID.fromString("5f5e9426-74f2-4675-b422-be6c3870cab4"),
			"Lorum ipsum...",
			4,
			List.of(true, false),
			Map.of("A", "Z", "B", "Y"),
			Instant.EPOCH,
			Optional.of("maybe"),
			Optional.empty());
	private static final String UNKNOWN_EVENT_TYPE = "net.albinoloverats.messaging.other.Unknown";
	private static final UUID MESSAGE_ID = UUID.fromString("9d252d74-4506-4769-b599-888a7c671a2b");

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
	void verify_known_event_serialisation()
	{
		val buffer = MessageSerialiser.serialiseEvent(EVENT, MESSAGE_ID, MessageType.EVENT);
		val length = buffer.getInt();
		assertTrue(length > 0);
		assertTrue(KNOWN_EVENT_JSON.length() >= length);
		val bytes = new byte[length];
		buffer.get(bytes);
		val json = new String(bytes, StandardCharsets.UTF_8);
		JSONAssert.assertEquals(KNOWN_EVENT_JSON, json, NON_EXTENSIBLE);
	}

	@Test
	@SneakyThrows
	void verify_known_event_deserialisation()
	{
		val buffer = ByteBuffer.allocate(KNOWN_EVENT_JSON.length() + Integer.BYTES);
		buffer.putInt(KNOWN_EVENT_JSON.length());
		buffer.put(KNOWN_EVENT_JSON.getBytes(StandardCharsets.UTF_8));
		buffer.flip();
		val triple = MessageSerialiser.extractMetadata(buffer);
		val clazz = triple.getLeft();
		val id = triple.getMiddle();
		val type = triple.getRight();
		assertEquals(EVENT.getClass().getCanonicalName(), clazz);
		assertEquals(MESSAGE_ID, id);
		assertEquals(MessageType.EVENT, type);
		val event = MessageSerialiser.deserialiseEvent(buffer);
		assertEquals(EVENT, event);
	}

	@Test
	@SneakyThrows
	void verify_appending_message_id_and_type()
	{
		val buffer = MessageSerialiser.serialiseEvent(EVENT, MESSAGE_ID, MessageType.EVENT);
		val length = buffer.getInt();
		assertTrue(length > 0);
		assertTrue(KNOWN_EVENT_JSON.length() >= length);
		val bytes = new byte[length];
		buffer.get(bytes);
		val json = new String(bytes, StandardCharsets.UTF_8);
		JSONAssert.assertEquals(KNOWN_EVENT_JSON, json, NON_EXTENSIBLE);
	}

	@Test
	@SneakyThrows
	void verify_unknown_event_deserialisation()
	{
		val buffer = ByteBuffer.allocate(UNKNOWN_EVENT_JSON.length() + Integer.BYTES);
		buffer.putInt(UNKNOWN_EVENT_JSON.length());
		buffer.put(UNKNOWN_EVENT_JSON.getBytes(StandardCharsets.UTF_8));
		buffer.flip();
		val triple = MessageSerialiser.extractMetadata(buffer);
		val clazz = triple.getLeft();
		val id = triple.getMiddle();
		val type = triple.getRight();
		assertEquals(UNKNOWN_EVENT_TYPE, clazz);
		assertEquals(MESSAGE_ID, id);
		assertEquals(MessageType.EVENT, type);
	}
}
