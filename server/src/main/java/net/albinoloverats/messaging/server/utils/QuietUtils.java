package net.albinoloverats.messaging.server.utils;

import com.jcabi.aspects.Quietly;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

@Slf4j
@UtilityClass
final public class QuietUtils
{
	@Quietly
	@SneakyThrows
	public static void quietSleep(Duration duration)
	{
		Thread.sleep(duration);
	}

	@Quietly
	@SneakyThrows
	public static void quietJoin(Thread thread, Duration duration)
	{
		thread.join(duration);
	}
}
