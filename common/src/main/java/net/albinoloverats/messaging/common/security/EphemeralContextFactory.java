package net.albinoloverats.messaging.common.security;

import com.jcabi.aspects.Loggable;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;
import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import static net.albinoloverats.messaging.common.utils.Constants.CONTEXT_PROTOCOL;
import static net.albinoloverats.messaging.common.utils.Constants.KEY_ALGORITHM;
import static net.albinoloverats.messaging.common.utils.Constants.KEY_STORE_TYPE;
import static net.albinoloverats.messaging.common.utils.Constants.KEY_TRUST_TYPE;
import static net.albinoloverats.messaging.common.utils.Constants.ONE_MINUTE;
import static net.albinoloverats.messaging.common.utils.Constants.ONE_YEAR;
import static net.albinoloverats.messaging.common.utils.Constants.SECURITY_PROVIDER;
import static net.albinoloverats.messaging.common.utils.Constants.SIGNING_ALGORITHM;

/**
 * Utility/factory class to create SSL context from in-memory keys/data.
 */
@UtilityClass
@Slf4j
public final class EphemeralContextFactory extends ContextFactory
{
	private static final String ALIAS = "selfsigned";
	private static final char[] PASSWORD = "b0f53e00037671f6980d9a810a22d6e3".toCharArray();

	/**
	 * Create SSL context from given configuration.
	 *
	 * @param commonName The common name to use for key creation.
	 * @param protocol   The SSL protocol version to use.
	 * @return A newly created SSL context.
	 * @throws IOException               Thrown on PEM key/store file error.
	 * @throws GeneralSecurityException  General security exception.
	 * @throws OperatorCreationException Operator creation exception.
	 */
	@Loggable(value = Loggable.TRACE, prepend = true)
	public static SSLContext create(@NonNull String commonName, @NonNull Optional<String> protocol)
			throws GeneralSecurityException, OperatorCreationException, IOException
	{
		/*
		 * Generate keypair
		 */
		val keyPairGenerator = KeyPairGenerator.getInstance(KEY_ALGORITHM);
		keyPairGenerator.initialize(2048);
		val keyPair = keyPairGenerator.generateKeyPair();
		/*
		 * Certificate details
		 */
		val now = Instant.now();
		val notBefore = new Date(now.minus(ONE_MINUTE).toEpochMilli());
		val notAfter = new Date(now.plus(ONE_YEAR).toEpochMilli());
		val subject = new X500Principal("CN=" + commonName);
		/*
		 * Build certificate (with BouncyCastle)
		 */
		val certBuilder = new JcaX509v3CertificateBuilder(
				subject,
				BigInteger.valueOf(now.toEpochMilli()),
				notBefore,
				notAfter,
				subject,
				keyPair.getPublic()
		);
		val signer = new JcaContentSignerBuilder(SIGNING_ALGORITHM)
				.build(keyPair.getPrivate());
		val cert = new JcaX509CertificateConverter()
				.setProvider(SECURITY_PROVIDER)
				.getCertificate(certBuilder.build(signer));
		cert.checkValidity(new Date());
		cert.verify(keyPair.getPublic());
		/*
		 * Put into in-memory keystore
		 */
		val keyStore = KeyStore.getInstance(KEY_STORE_TYPE);
		keyStore.load(null, null);
		keyStore.setKeyEntry(ALIAS, keyPair.getPrivate(), PASSWORD, new Certificate[]{ cert });
		val keyManagerFactory = KeyManagerFactory.getInstance(KEY_TRUST_TYPE);
		keyManagerFactory.init(keyStore, PASSWORD);
		val keyManagers = keyManagerFactory.getKeyManagers();
		val trustAll = new TrustManager[]
				{
						new X509TrustManager()
						{
							public X509Certificate[] getAcceptedIssuers()
							{
								return new X509Certificate[0];
							}

							public void checkClientTrusted(X509Certificate[] certs, String authType)
							{
							}

							public void checkServerTrusted(X509Certificate[] certs, String authType)
							{
							}
						}
				};
		/*
		 * Build SSLContext
		 */
		val sslContext = SSLContext.getInstance(protocol.orElse(CONTEXT_PROTOCOL));
		sslContext.init(keyManagers, trustAll, new SecureRandom());
		return sslContext;
	}
}

