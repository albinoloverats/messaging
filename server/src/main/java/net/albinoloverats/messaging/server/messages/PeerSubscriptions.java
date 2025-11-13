package net.albinoloverats.messaging.server.messages;

import java.util.Set;
import java.util.UUID;

public record PeerSubscriptions(UUID peerId, Set<String> eventSubscriptions, Set<String> querySubscriptions)
{
}
