package net.albinoloverats.messaging.common.security;

import com.jcabi.aspects.Loggable;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import net.albinoloverats.messaging.common.config.PEMConfig;
import org.apache.commons.lang3.ObjectUtils;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMEncryptedKeyPair;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder;
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;
import org.bouncycastle.pkcs.PKCSException;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStreamReader;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Optional;

import static net.albinoloverats.messaging.common.utils.Constants.CONTEXT_PROTOCOL;
import static net.albinoloverats.messaging.common.utils.Constants.KEY_STORE_TYPE;
import static net.albinoloverats.messaging.common.utils.Constants.SECURITY_PROVIDER;

/**
 * Utility/factory class to create SSL context from {@link PEMConfig}.
 */
@UtilityClass
@Slf4j
public final class PEMContextFactory extends ContextFactory
{
	/**
	 * Create SSL context from given configuration.
	 *
	 * @param pemConfig The PEM configuration.
	 * @param protocol  The SSL protocol version to use.
	 * @return A newly created SSL context.
	 * @throws IOException               Thrown on PEM key/store file error.
	 * @throws GeneralSecurityException  General security exception.
	 * @throws OperatorCreationException Operator creation exception.
	 * @throws PKCSException             Bouncy Castle related exception.
	 */
	@Loggable(value = Loggable.TRACE, prepend = true)
	public static SSLContext create(@NonNull PEMConfig pemConfig, @NonNull Optional<String> protocol)
			throws IOException, GeneralSecurityException, OperatorCreationException, PKCSException
	{
		val password = Optional.ofNullable(pemConfig.keyPassword())
				.map(String::toCharArray)
				.orElse(new char[0]);
		val privateKey = readPrivateKey(pemConfig.keyPath(), password);
		val cert = readCertificate(pemConfig.certificatePath());
		val trustCert = readCertificate(pemConfig.trustPath());

		KeyManager[] keyManagers = null;
		if (ObjectUtils.allNotNull(privateKey, cert))
		{
			// typical server config
			val keyStore = KeyStore.getInstance(KEY_STORE_TYPE);
			keyStore.load(null, null);
			keyStore.setKeyEntry("key", privateKey, new char[0], new Certificate[]{ cert });

			val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
			keyManagerFactory.init(keyStore, new char[0]);
			keyManagers = keyManagerFactory.getKeyManagers();
		}
		else if (!ObjectUtils.allNull(privateKey, cert))
		{
			throw new IllegalArgumentException("Private key and certificate are either both required or neither supplied");
		}

		TrustManager[] trustManagers = null;
		if (trustCert != null)
		{
			// typical client config
			val trustStore = KeyStore.getInstance(KEY_STORE_TYPE);
			trustStore.load(null, null);
			trustStore.setCertificateEntry("trust", trustCert);

			val defaultTrustAlgorithm = TrustManagerFactory.getDefaultAlgorithm();
			val trustManagerFactory = TrustManagerFactory.getInstance(defaultTrustAlgorithm);
			trustManagerFactory.init(trustStore);
			trustManagers = trustManagerFactory.getTrustManagers();
		}

		val sslContext = SSLContext.getInstance(protocol.orElse(CONTEXT_PROTOCOL));
		sslContext.init(keyManagers, trustManagers, null);
		return sslContext;
	}

	private static PrivateKey readPrivateKey(String keyFile, char[] password) throws IOException, PKCSException, OperatorCreationException
	{
		if (keyFile == null)
		{
			return null;
		}
		try (val inputStream = openStream(keyFile);
		     val reader = new InputStreamReader(inputStream);
		     val pemParser = new PEMParser(reader))
		{
			val object = pemParser.readObject();
			val converter = new JcaPEMKeyConverter()
					.setProvider(SECURITY_PROVIDER);
			switch (object)
			{
				case PEMEncryptedKeyPair encryptedKeyPair ->
				{
					val decryptor = new JcePEMDecryptorProviderBuilder()
							.build(password);
					val decryptedKeyPair = encryptedKeyPair.decryptKeyPair(decryptor);
					val keyPair = converter.getKeyPair(decryptedKeyPair);
					return keyPair.getPrivate();
				}
				case PEMKeyPair unencryptedKeyPair ->
				{
					val keyPair = converter.getKeyPair(unencryptedKeyPair);
					return keyPair.getPrivate();
				}
				case PrivateKeyInfo privateKeyInfo ->
				{
					// Handles unencrypted PKCS#8 keys
					return converter.getPrivateKey(privateKeyInfo);
				}
				case PKCS8EncryptedPrivateKeyInfo encryptedInfo ->
				{
					// Handles encrypted PKCS#8 keys
					val decryptor = new JceOpenSSLPKCS8DecryptorProviderBuilder()
							.build(password);
					val privateKeyInfo = encryptedInfo.decryptPrivateKeyInfo(decryptor);
					return converter.getPrivateKey(privateKeyInfo);
				}
				default -> throw new IllegalArgumentException("Unsupported PEM object: " + object.getClass().getName());
			}
		}
	}

	private static X509Certificate readCertificate(String certificateFile) throws IOException, GeneralSecurityException
	{
		if (certificateFile == null)
		{
			return null;
		}
		val certificateFactory = CertificateFactory.getInstance("X.509");
		try (val inputStream = openStream(certificateFile))
		{
			return (X509Certificate)certificateFactory.generateCertificate(inputStream);
		}
	}
}
