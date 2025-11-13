package net.albinoloverats.messaging.server.messages;

import net.albinoloverats.messaging.common.annotations.Event;

import java.util.UUID;

@Event
public record PeerRegistration(UUID peerId)
{
}
