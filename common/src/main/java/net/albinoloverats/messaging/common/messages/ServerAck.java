package net.albinoloverats.messaging.common.messages;

import java.util.UUID;

/**
 * Message from server to client after successful connection; includes server
 * ID.
 *
 * @param serverId The server ID.
 */
public record ServerAck(UUID serverId)
{
}
