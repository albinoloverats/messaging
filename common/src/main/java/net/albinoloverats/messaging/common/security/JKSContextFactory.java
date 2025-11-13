package net.albinoloverats.messaging.common.security;

import com.jcabi.aspects.Loggable;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import net.albinoloverats.messaging.common.config.JKSConfig;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Optional;

import static net.albinoloverats.messaging.common.utils.Constants.CONTEXT_PROTOCOL;
import static net.albinoloverats.messaging.common.utils.Constants.KEY_STORE_TYPE;

/**
 * Utility/factory class to create SSL context from {@link JKSConfig}.
 */
@UtilityClass
@Slf4j
public final class JKSContextFactory extends ContextFactory
{
	/**
	 * Create SSL context from given configuration.
	 *
	 * @param jksConfig The JKS configuration.
	 * @param protocol  The SSL protocol version to use.
	 * @return A newly created SSL context.
	 * @throws IOException              Thrown on PEM key/store file error.
	 * @throws GeneralSecurityException General security exception.
	 */
	@Loggable(value = Loggable.TRACE, prepend = true)
	public static SSLContext create(@NonNull JKSConfig jksConfig, @NonNull Optional<String> protocol) throws IOException, GeneralSecurityException
	{
		KeyManager[] keyManagers = null;
		val keyStorePath = jksConfig.keyStorePath();
		if (keyStorePath != null)
		{
			// typical server config
			val password = jksConfig.keyStorePassword();
			keyManagers = loadKeyManagers(keyStorePath, password);
		}

		TrustManager[] trustManagers = null;
		val trustStorePath = jksConfig.trustStorePath();
		if (trustStorePath != null)
		{
			// typical client config
			val password = jksConfig.trustStorePassword();
			trustManagers = loadTrustManagers(trustStorePath, password);
		}

		val context = SSLContext.getInstance(protocol.orElse(CONTEXT_PROTOCOL));
		context.init(keyManagers, trustManagers, new SecureRandom());
		return context;
	}

	private static KeyManager[] loadKeyManagers(@NonNull String path, @NonNull String password) throws IOException, GeneralSecurityException
	{
		val keyStore = loadKeyStore(path, password);
		val defaultAlgorithm = KeyManagerFactory.getDefaultAlgorithm();
		val keyManagerFactory = KeyManagerFactory.getInstance(defaultAlgorithm);
		val chars = password.toCharArray();
		keyManagerFactory.init(keyStore, chars);
		return keyManagerFactory.getKeyManagers();
	}

	private static TrustManager[] loadTrustManagers(@NonNull String path, @NonNull String password) throws IOException, GeneralSecurityException
	{
		val keyStore = loadKeyStore(path, password);
		val defaultAlgorithm = TrustManagerFactory.getDefaultAlgorithm();
		val trustManagerFactory = TrustManagerFactory.getInstance(defaultAlgorithm);
		trustManagerFactory.init(keyStore);
		return trustManagerFactory.getTrustManagers();
	}

	private static KeyStore loadKeyStore(@NonNull String path, @NonNull String password) throws IOException, GeneralSecurityException
	{
		val lower = path.toLowerCase(Locale.ROOT);
		if (lower.endsWith(".jks"))
		{
			return loadKeyStore(KEY_STORE_TYPE, path, password);
		}
		else if (lower.endsWith(".p12") || lower.endsWith(".pfx"))
		{
			return loadKeyStore("PKCS12", path, password);
		}
		else
		{
			throw new IllegalArgumentException("Unsupported keystore format for: " + path);
		}
	}

	private static KeyStore loadKeyStore(@NonNull String keyStoreType, @NonNull String path, @NonNull String password) throws IOException, GeneralSecurityException
	{
		val keyStore = KeyStore.getInstance(keyStoreType);
		try (val inputStream = openStream(path))
		{
			val array = password.toCharArray();
			keyStore.load(inputStream, array);
		}
		return keyStore;
	}
}
