package net.albinoloverats.messaging.common.functions;

import com.jcabi.aspects.Quietly;
import lombok.SneakyThrows;

/**
 * Functional interface for doing something and swallowing the exception.
 */
@FunctionalInterface
public interface QuietRunnable
{
	void run() throws Throwable;

	@Quietly
	@SneakyThrows
	static void doQuietly(QuietRunnable runnable)
	{
		runnable.run();
	}
}
