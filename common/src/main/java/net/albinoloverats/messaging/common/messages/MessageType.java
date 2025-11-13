package net.albinoloverats.messaging.common.messages;

import java.util.Arrays;

/**
 * Message types: events, queries, query responses.
 */
public enum MessageType
{
	EVENT,
	QUERY,
	RESPONSE;

	public static MessageType fromString(String s)
	{
		return Arrays.stream(MessageType.values())
				.filter(messageType -> messageType.name().equals(s))
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Invalid message type: " + s));
	}
}
