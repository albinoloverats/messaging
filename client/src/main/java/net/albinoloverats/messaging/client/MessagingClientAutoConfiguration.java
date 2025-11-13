package net.albinoloverats.messaging.client;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import net.albinoloverats.messaging.client.config.OnMessagingEnabledCondition;
import net.albinoloverats.messaging.common.config.MessagingProperties;
import net.albinoloverats.messaging.common.security.EphemeralContextFactory;
import net.albinoloverats.messaging.common.security.JKSContextFactory;
import net.albinoloverats.messaging.common.security.PEMContextFactory;
import org.apache.commons.lang3.StringUtils;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.pkcs.PKCSException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Spring Boot autoconfiguration class for {@link MessagingClient}.
 */
@AutoConfiguration
@EnableConfigurationProperties(MessagingProperties.class)
@Conditional(OnMessagingEnabledCondition.class)
@Slf4j
@RequiredArgsConstructor
final class MessagingClientAutoConfiguration
{
	private MessagingClient messagingClient;

	@Bean
	AnnotatedEventDispatcher annotatedEventDispatcher(ApplicationContext applicationContext)
	{
		return new AnnotatedEventDispatcher(applicationContext);
	}

	@Bean
	@ConditionalOnMissingBean(MeterRegistry.class)
	MeterRegistry simpleMeterRegistry()
	{
		return new SimpleMeterRegistry();
	}

	@ConditionalOnProperty(name = "spring.application.name")
	@Bean
	MessagingClient messagingClient(@Value("${spring.application.name}") String applicationName,
	                                MessagingProperties properties,
	                                AnnotatedEventDispatcher eventDispatcher,
	                                MeterRegistry meterRegistry)
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

			val hosts = properties.hosts()
					.stream()
					.map(StringUtils::toRootLowerCase)
					.collect(Collectors.toSet());
			log.info("Auto-configuring MessagingClient with hosts: {}, clientId: {}", hosts, applicationName);
			messagingClient = new MessagingClient(applicationName,
					properties,
					sslContext,
					eventDispatcher,
					meterRegistry);
			messagingClient.start();
		}
		catch (GeneralSecurityException
		       | IOException
		       | OperatorCreationException
		       | PKCSException e)
		{
			log.error("Failed to start MessagingClient", e);
			throw new RuntimeException(e);
		}
		return messagingClient;
	}

	@Bean
	public MessagingGateway messagingGateway(MeterRegistry meterRegistry, MessagingClient messagingClient)
	{
		return new MessagingGateway(meterRegistry, messagingClient);
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
