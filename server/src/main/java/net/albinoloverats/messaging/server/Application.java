package net.albinoloverats.messaging.server;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import net.albinoloverats.messaging.common.config.MessagingProperties;
import net.albinoloverats.messaging.common.exceptions.SerialisableException;
import net.albinoloverats.messaging.common.messages.SubscribeToEvents;
import net.albinoloverats.messaging.common.security.EphemeralContextFactory;
import net.albinoloverats.messaging.common.security.JKSContextFactory;
import net.albinoloverats.messaging.common.security.PEMContextFactory;
import net.albinoloverats.messaging.common.utils.MessageSerialiser;
import net.albinoloverats.messaging.server.messages.PeerRelay;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.pkcs.PKCSException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import tools.jackson.core.JacksonException;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.HashSet;
import java.util.Optional;

@Slf4j
@SpringBootApplication(scanBasePackages = "net.albinoloverats.messaging")
@EnableConfigurationProperties(MessagingProperties.class)
public class Application
{
	private static final String DOCKER_CONFIG = "/opt/messaging/config.yaml";

	private MessagingServer messagingServer;

	public static void main(String[] args)
	{
		val basePackages = new HashSet<String>();
		basePackages.add(SubscribeToEvents.class.getPackageName());
		basePackages.add(PeerRelay.class.getPackageName());
		basePackages.add(SerialisableException.class.getPackageName());
		basePackages.add("java.math"); // for BigDecimal or BigInteger
		basePackages.add("java.time"); // for Instant and others...
		basePackages.add("java.util"); // for Set, UUID, maybe more...
		MessageSerialiser.initialise(basePackages);
		SpringApplication.run(Application.class, args);
	}

	@Bean
	MessagingServer messagingServer(MessagingProperties properties, MeterRegistry meterRegistry)
	{
		try
		{
			val yamlMapper = YAMLMapper.builder()
					.findAndAddModules()
					.build();

			val configFile = new File(DOCKER_CONFIG);
			if (configFile.exists() && configFile.isFile() && configFile.canRead())
			{
				val dockerConfig = yamlMapper.readValue(configFile, MessagingProperties.DockerWrapper.class);
				log.info("Replacing application.yaml config with {}: {}", DOCKER_CONFIG, dockerConfig);
				properties = dockerConfig.messaging();

			}
			else if (!configFile.isFile())
			{
				log.warn("{} was unexpectedly not a file!", DOCKER_CONFIG);
			}
			else if (!configFile.canRead())
			{
				log.warn("Could not read config file {}", DOCKER_CONFIG);
			}
		}
		catch (JacksonException e)
		{
			log.warn("Could not parse configuration file : {}", DOCKER_CONFIG, e);
		}

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

			messagingServer = new MessagingServer(properties, sslContext, meterRegistry);
			messagingServer.start();
		}
		catch (GeneralSecurityException
		       | IOException
		       | OperatorCreationException
		       | PKCSException e)
		{
			log.error("Failed to start MessagingServer", e);
			System.exit(1);
		}
		return messagingServer;
	}

	@PreDestroy
	public void onExit()
	{
		if (messagingServer != null)
		{
			messagingServer.stop();
		}
		log.info("MessagingServer stopping, shutting down.");
	}
}
