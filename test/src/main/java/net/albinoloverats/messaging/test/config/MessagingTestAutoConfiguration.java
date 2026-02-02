package net.albinoloverats.messaging.test.config;

import lombok.extern.slf4j.Slf4j;
import lombok.val;
import net.albinoloverats.messaging.client.MessagingGateway;
import net.albinoloverats.messaging.client.client.DefaultMessagingClient;
import net.albinoloverats.messaging.client.client.MessagingClient;
import net.albinoloverats.messaging.client.config.AnnotatedEventDispatcher;
import net.albinoloverats.messaging.test.TestMessagingHarness;
import net.albinoloverats.messaging.test.client.TestMessagingClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.io.IOException;
import java.util.concurrent.Executor;

@AutoConfiguration
@Slf4j
public final class MessagingTestAutoConfiguration
{
	@Bean
	Executor testMessagingEventExecutor()
	{
		return Runnable::run;
	}

	@Bean
	@Primary
	AnnotatedEventDispatcher testMessagingDispatcher(ApplicationContext applicationContext,
	                                                 @Qualifier("testMessagingEventExecutor") Executor eventExecutor)
	{
		return new AnnotatedEventDispatcher(applicationContext, eventExecutor);
	}

	@Bean("testMessagingClient")
	MessagingClient testMessagingClient(ApplicationContext applicationContext,
	                                    AnnotatedEventDispatcher eventDispatcher)
	{
		val client = new TestMessagingClient(eventDispatcher);
		TestMessagingHarness.setClient(client);
		return client;
	}

	@Bean
	@Primary
	public MessagingGateway testMessagingGateway(ApplicationContext applicationContext,
	                                             @Qualifier("testMessagingClient") MessagingClient testClient,
	                                             @Qualifier("messagingClient") MessagingClient messagingClient)
	{
		if (useLiveClient(applicationContext))
		{
			if (messagingClient instanceof DefaultMessagingClient liveClient)
			{
				try
				{
					liveClient.start();
				}
				catch (IOException e)
				{
					log.error("Failed to start MessagingClient", e);
					throw new RuntimeException(e);
				}
				TestMessagingHarness.setLiveClient(true);
				log.warn("Injecting live MessagingClient: {}", liveClient);
				return new MessagingGateway(liveClient);
			}
		}
		return new MessagingGateway(testClient);
	}

	private boolean useLiveClient(ApplicationContext applicationContext)
	{
		val useLiveClient = applicationContext.getEnvironment()
				.getProperty("messaging.test.live");
		return Boolean.parseBoolean(useLiveClient);
	}
}
