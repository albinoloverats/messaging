package net.albinoloverats.messaging.client.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import net.albinoloverats.messaging.client.MessagingGateway;
import net.albinoloverats.messaging.client.client.DefaultMessagingClient;
import net.albinoloverats.messaging.client.client.MessagingClient;
import net.albinoloverats.messaging.common.config.MessagingProperties;
import net.albinoloverats.messaging.common.security.EphemeralContextFactory;
import net.albinoloverats.messaging.common.security.JKSContextFactory;
import net.albinoloverats.messaging.common.security.PEMContextFactory;
import org.apache.commons.lang3.StringUtils;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.pkcs.PKCSException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Spring Boot autoconfiguration class for {@link MessagingClient}.
 */
@AutoConfiguration
@EnableConfigurationProperties(MessagingProperties.class)
@Conditional(OnMessagingEnabledCondition.class)
@Slf4j
public final class MessagingClientAutoConfiguration
{
	private DefaultMessagingClient messagingClient;

	@Bean
	Executor messagingEventExecutor()
	{
		return Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2);
	}

	@Bean
	AnnotatedEventDispatcher messagingDispatcher(ApplicationContext applicationContext,
	                                             @Qualifier("messagingEventExecutor") Executor eventExecutor)
	{
		return new AnnotatedEventDispatcher(applicationContext, eventExecutor);
	}

	@Bean
	@ConditionalOnMissingBean(MeterRegistry.class)
	MeterRegistry simpleMeterRegistry()
	{
		return new SimpleMeterRegistry();
	}

	@Bean
	SSLContext messagingSslContext(MessagingProperties properties)
	{
		try
		{
			val ssl = properties.ssl();
			val protocol = Optional.ofNullable(ssl)
					.map(MessagingProperties.SSL::protocol);
			var sslContext = EphemeralContextFactory.create("localhost", protocol);
			if (ssl != null)
			{
				if (ssl.jks() != null)
				{
					sslContext = JKSContextFactory.create(properties.ssl().jks(), protocol);
				}
				else if (ssl.pem() != null)
				{
					sslContext = PEMContextFactory.create(properties.ssl().pem(), protocol);
				}
			}
			return sslContext;
		}
		catch (GeneralSecurityException
		       | IOException
		       | OperatorCreationException
		       | PKCSException e)
		{
			log.error("Failed to start MessagingClient", e);
			throw new RuntimeException(e);
		}
	}

	@Bean
	@ConditionalOnProperty(name = "spring.application.name")
	MessagingClient messagingClient(@Value("${spring.application.name}") String applicationName,
	                                MessagingProperties properties,
	                                AnnotatedEventDispatcher eventDispatcher,
	                                @Qualifier("messagingSslContext") SSLContext sslContext,
	                                MeterRegistry meterRegistry)
	{
		try
		{
			val hosts = properties.hosts()
					.stream()
					.map(StringUtils::toRootLowerCase)
					.collect(Collectors.toSet());
			log.info("Auto-configuring MessagingClient with hosts: {}, clientId: {}", hosts, applicationName);
			messagingClient = new DefaultMessagingClient(applicationName,
					properties,
					sslContext,
					eventDispatcher,
					meterRegistry);
		}
		catch (IOException e)
		{
			log.error("Failed to instantiate MessagingClient", e);
			throw new RuntimeException(e);
		}
		return messagingClient;
	}

	@Bean
	public MessagingGateway messagingGateway(MessagingClient messagingClient)
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
		}
		return new MessagingGateway(messagingClient);
	}

	@PreDestroy
	public void shutdownClient()
	{
		if (messagingClient != null)
		{
			messagingClient.stop();
			log.info("Auto-configured MessagingClient stopped.");
		}
	}
}
