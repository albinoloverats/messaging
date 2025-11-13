package net.albinoloverats.messaging.common.messages;

import net.albinoloverats.messaging.common.annotations.Event;

import java.util.Set;
import java.util.UUID;

/**
 * Message to indicate to server which events/queries a client would like to handle.
 *
 * @param serviceName          The client service name.
 * @param serviceId            The client service ID
 * @param subscribedEventTypes Event types the client can handle.
 * @param subscribedQueryTypes Query types the client can handle.
 */
@Event
public record SubscribeToEvents(String serviceName,
                                UUID serviceId,
                                Set<String> subscribedEventTypes,
                                Set<String> subscribedQueryTypes)
{
}
