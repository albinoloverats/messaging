package net.albinoloverats.messaging.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

import static net.albinoloverats.messaging.common.utils.Constants.AUTO_DISCOVERY;

/**
 * Configuration for both messaging server and clients.
 *
 * @param hosts Set of known hosts to connect to.
 * @param ssl   Configuration options for SSL.
 */
@ConfigurationProperties(prefix = "messaging")
public record MessagingProperties(Set<String> hosts, SSL ssl)
{
	/**
	 * Get known hosts, or auto if null/empty.
	 *
	 * @return Known hosts.
	 */
	public Set<String> hosts()
	{
		if (hosts == null || hosts.isEmpty())
		{
			return Set.of(AUTO_DISCOVERY);
		}
		return hosts;
	}

	/**
	 * SSL configuration settings.
	 *
	 * @param protocol SSL protocol version to use.
	 * @param jks      JKS configuration
	 * @param pem      PEM configuration
	 */
	public record SSL(String protocol, JKSConfig jks, PEMConfig pem)
	{
	}

	/**
	 * Wrapper for docker-ized configuration.
	 *
	 * @param messaging The configuration.
	 */
	public record DockerWrapper(MessagingProperties messaging)
	{
	}
}
