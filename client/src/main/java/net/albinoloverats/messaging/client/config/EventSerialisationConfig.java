package net.albinoloverats.messaging.client.config;

import lombok.extern.slf4j.Slf4j;
import lombok.val;
import net.albinoloverats.messaging.client.exceptions.QueryException;
import net.albinoloverats.messaging.common.exceptions.SerialisableException;
import net.albinoloverats.messaging.common.messages.SubscribeToEvents;
import net.albinoloverats.messaging.common.utils.MessageSerialiser;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashSet;

@Slf4j
@Component
final public class EventSerialisationConfig implements InitializingBean
{
	/*
	 * Spring Boot's main application class (e.g., com.example.MyApplication). We assume the @SpringBootApplication is
	 * in the main package to scan.
	 *
	 * We need this to trigger the value injection for main class.
	 */
	@Value("${spring.main.web-application-type:SERVLET}")
	private String webApplicationType;
	/*
	 * We can't directly get the main class package via @Value easily. A more robust way is to infer it or get it from
	 * ApplicationContext. For simplicity, let's assume the main application package is known or configurable. A common
	 * heuristic is to take the package of the class annotated with @SpringBootApplication. However, @Value cannot
	 * extract this directly.
	 *
	 * A more reliable way is to get the root package from the ClassLoader or from a known convention. For a library,
	 * sometimes the user provides this. For simplicity, let's assume the user will configure a property, or we default
	 * to a very wide but controlled scan.
	 *
	 * Let's use a configurable property for the base scan package, or a sensible default. User can set this property.
	 * Empty string means infer.
	 */
	@Value("${messaging.eventData.scan-base-package:}")
	private String configuredScanBasePackage;

	@Override
	public void afterPropertiesSet()
	{
		val basePackages = new HashSet<String>();
		basePackages.add(SubscribeToEvents.class.getPackageName());
		basePackages.add(QueryException.class.getPackageName());
		basePackages.add(SerialisableException.class.getPackageName());
		basePackages.add("java.math"); // for BigDecimal or BigInteger
		basePackages.add("java.time"); // for Instant and others...
		basePackages.add("java.util"); // for Set, UUID, maybe more...

		String inferredAppPackage = null;
		if (StringUtils.hasText(configuredScanBasePackage))
		{
			inferredAppPackage = configuredScanBasePackage;
		}
		else
		{
			/*
			 * Heuristic: find the package of the @SpringBootApplication class
			 */
			try
			{
				val stackTrace = Thread.currentThread().getStackTrace();
				for (val element : stackTrace)
				{
					try
					{
						val clazz = Class.forName(element.getClassName());
						if (clazz.isAnnotationPresent(org.springframework.boot.autoconfigure.SpringBootApplication.class))
						{
							inferredAppPackage = clazz.getPackage().getName();
							break;
						}
					}
					catch (ClassNotFoundException e)
					{
						/*
						 * Ignore, just means this stack trace element isn't a class we can load
						 */
					}
				}
			}
			catch (Exception e)
			{
				log.warn("Could not infer Spring Boot application base package for eventData scanning. Error: {}", e.getMessage());
			}

			if (!StringUtils.hasText(inferredAppPackage))
			{
				inferredAppPackage = findCallingApplicationPackage(); // Helper from previous iteration
			}
		}

		if (StringUtils.hasText(inferredAppPackage))
		{
			basePackages.add(inferredAppPackage);
		}
		else
		{
			log.warn("Could not determine application base package for @Event or @Response class scanning. Only common events will be scanned. " +
					"Please consider setting 'messaging.eventData.scan-base-package' property if your events are not in '{}'.", SubscribeToEvents.class.getPackageName());
		}

		if (!basePackages.isEmpty())
		{
			MessageSerialiser.initialise(basePackages);
		}
		else
		{
			log.error("No packages to scan for @Event or @Response classes. Event deserialisation will likely fail.");
		}
	}

	/**
	 * Helper method to try and find a sensible default for the calling application's base package
	 */
	private String findCallingApplicationPackage()
	{
		/*
		 * This is a heuristic to find a package that is likely the application's root. It looks for a class in the main
		 * thread's stack trace that isn't from Spring or JDK.
		 */
		for (val element : Thread.currentThread().getStackTrace())
		{
			val className = element.getClassName();
			if (!className.startsWith("java.") &&
					!className.startsWith("javax.") &&
					!className.startsWith("org.springframework."))
			{
				try
				{
					/*
					 * Try to load the class and get its package
					 */
					val clazz = Class.forName(className);
					val pkg = clazz.getPackage();
					if (pkg != null)
					{
						return pkg.getName();
					}
				}
				catch (ClassNotFoundException e)
				{
					/*
					 * Ignore, continue search
					 */
				}
			}
		}
		return null; // Could not infer
	}
}
