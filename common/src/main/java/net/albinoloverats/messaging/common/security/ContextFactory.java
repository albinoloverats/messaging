package net.albinoloverats.messaging.common.security;

import lombok.NonNull;
import lombok.val;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Security;

import static net.albinoloverats.messaging.common.utils.Constants.SECURITY_PROVIDER;

/**
 * Common SSL context factory methods.
 */
abstract class ContextFactory
{
	static
	{
		if (Security.getProvider(SECURITY_PROVIDER) == null)
		{
			Security.addProvider(new BouncyCastleProvider());
		}
	}

	/**
	 * Get the given path as an {@link InputStream}.
	 *
	 * @param path The path to read from.
	 * @return An {@link InputStream}.
	 * @throws IOException Thrown if the given path cannot be read from.
	 */
	protected static InputStream openStream(@NonNull String path) throws IOException
	{
		if (path.startsWith("classpath:"))
		{
			val resource = path.substring("classpath:".length());
			val inputStream = Thread.currentThread()
					.getContextClassLoader()
					.getResourceAsStream(resource);
			if (inputStream == null)
			{
				throw new FileNotFoundException("Classpath resource not found: " + resource);
			}
			return inputStream;
		}
		val filePath = Path.of(path);
		if (!Files.exists(filePath))
		{
			throw new FileNotFoundException("File not found: " + path);
		}
		return Files.newInputStream(filePath);
	}
}
