package net.albinoloverats.messaging.server.messages;

import net.albinoloverats.messaging.common.annotations.Event;

import java.util.UUID;

@Event
public record PeerDisconnect(UUID peerId, boolean isDeduplication)
{
	PeerDisconnect(UUID peerId)
	{
		this(peerId, false);
	}
}
