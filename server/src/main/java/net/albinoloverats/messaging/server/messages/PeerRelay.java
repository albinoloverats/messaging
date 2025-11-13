package net.albinoloverats.messaging.server.messages;

import net.albinoloverats.messaging.common.annotations.Event;

import java.util.UUID;

@Event
public record PeerRelay(UUID id, byte[] event)
{
	public PeerRelay(byte[] event)
	{
		this(UUID.randomUUID(), event);
	}
}
