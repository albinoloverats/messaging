package net.albinoloverats.messaging.common.messages;

/**
 * Message from server to client after successful message handling.
 *
 * @param eventType The event message type.
 */
public record ClientAck(String eventType)
{
}
