package net.albinoloverats.messaging.common.config;

/**
 * JKS Configuration.
 *
 * @param keyStorePath       Path to key store.
 * @param keyStorePassword   Key store password.
 * @param trustStorePath     Path to trust store.
 * @param trustStorePassword Trust store password.
 */
public record JKSConfig(String keyStorePath,
                        String keyStorePassword,
                        String trustStorePath,
                        String trustStorePassword)
{
}
