package net.albinoloverats.messaging.client.client;

import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface MessagingClient
{
	default UUID getClientId()
	{
		return UUID.randomUUID();
	}

	default void handleIncomingMessage(ByteBuffer buffer)
	{
		// This just means we don't have an empty method in the test client
		throw new IllegalStateException("No handler implementation for incoming messages");
	}

	CompletableFuture<Void> sendMessage(Object message);

	<R> CompletableFuture<R> sendMessageWantResponse(Object message);
}
