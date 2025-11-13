package net.albinoloverats.messaging.common.config;

/**
 * PEM configuration.
 *
 * @param keyPath         Path to private key.
 * @param keyPassword     Private key password.
 * @param certificatePath Path to certificate.
 * @param trustPath       Path to trust store.
 */
public record PEMConfig(String keyPath,
                        String keyPassword,
                        String certificatePath,
                        String trustPath
)
{
}
