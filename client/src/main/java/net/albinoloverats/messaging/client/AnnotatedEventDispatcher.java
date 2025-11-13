package net.albinoloverats.messaging.client;

import com.jcabi.aspects.Loggable;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import net.albinoloverats.messaging.client.exceptions.QueryException;
import net.albinoloverats.messaging.common.annotations.Event;
import net.albinoloverats.messaging.common.annotations.EventHandler;
import net.albinoloverats.messaging.common.annotations.Query;
import net.albinoloverats.messaging.common.annotations.QueryHandler;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dispatches incoming events to methods annotated with {@link EventHandler}.
 * It discovers handlers at application startup and manages their invocation.
 */
@Slf4j
@RequiredArgsConstructor
final class AnnotatedEventDispatcher implements ApplicationListener<ContextRefreshedEvent>
{
	private final ApplicationContext applicationContext;

	private final ExecutorService eventExecutorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2);
	private final ExecutorService queryExecutorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

	/*
	 * Map from event type name (String) to a list of HandlerMethod (Object instance + Method)
	 */
	private final Map<String, List<HandlerMethod>> eventHandlersMap = new HashMap<>();
	/*
	 * Map from event type name (String) to a HandlerMethod (Object instance + Method)
	 */
	private final Map<String, HandlerMethod> queryHandlerMap = new HashMap<>();
	/*
	 * Set of all unique event type identifiers found, used for initial subscription
	 */
	@Getter
	private final Set<String> subscribedEventTypes = new HashSet<>();
	/*
	 * Set of all unique event type identifiers found, used for initial subscription
	 */
	@Getter
	private final Set<String> subscribedQueryTypes = new HashSet<>();

	/**
	 * Called by Spring when the application context has been refreshed.
	 * This is the ideal time to scan for @EventHandler methods.
	 */
	@Override
	@Loggable(value = Loggable.TRACE, prepend = true)
	public void onApplicationEvent(ContextRefreshedEvent event)
	{
		if (event.getApplicationContext() == this.applicationContext)
		{
			log.debug("Scanning for @EventHandler and @QueryHandler methods...");
			scanAndRegisterHandlers();
		}
	}

	/**
	 * Scans all Spring beans for methods annotated with @EventHandler.
	 */
	@Loggable(value = Loggable.TRACE, prepend = true)
	private void scanAndRegisterHandlers()
	{
		val beanNames = applicationContext.getBeanDefinitionNames();
		for (val beanName : beanNames)
		{
			val bean = applicationContext.getBean(beanName);
			val beanClass = bean.getClass();

			/*
			 * Iterate over all methods, including inherited ones, but prefer specific implementations
			 */
			ReflectionUtils.doWithMethods(beanClass, method ->
			{
				val methodName = method.getName();
				val isEventHandler = method.isAnnotationPresent(EventHandler.class);
				val isQueryHandler = method.isAnnotationPresent(QueryHandler.class);
				if (isEventHandler && isQueryHandler)
				{
					log.warn("Handler method {} in bean {} cannot be both event and query. Skipping.", methodName, beanName);
					return;
				}
				if (isEventHandler || isQueryHandler)
				{
					val applicationName = StringUtils.defaultIfEmpty(applicationContext.getApplicationName(), applicationContext.getId());
					if (method.getParameterCount() != 1)
					{
						log.warn("Handler method {} in bean {} must have exactly one parameter. Skipping.", methodName, beanName);
						return;
					}
					val eventType = method.getParameterTypes()[0];
					val eventAnnotation = eventType.getAnnotation(Event.class);
					val queryAnnotation = eventType.getAnnotation(Query.class);
					if (eventAnnotation == null && queryAnnotation == null)
					{
						log.warn("Parameter type {} of Handler method {} in bean {} is not annotated with @Event or @Query. Skipping.", eventType.getName(), methodName, beanName);
						return;
					}

					/*
					 * Determine the logical event type identifier
					 */
					val eventTypeIdentifier = eventType.getName();
					ReflectionUtils.makeAccessible(method);
					/*
					 * Store the handler method
					 */
					if (isEventHandler)
					{
						eventHandlersMap.computeIfAbsent(eventTypeIdentifier, k -> Collections.synchronizedList(new ArrayList<>()))
								.add(new HandlerMethod(bean, method, eventType));
						subscribedEventTypes.add(eventTypeIdentifier);
					}
					else
					{
						if (queryHandlerMap.containsKey(eventTypeIdentifier))
						{
							log.warn("Already registered query handler {} in bean {} for event type: {}", methodName, beanName, eventTypeIdentifier);
						}
						else
						{
							queryHandlerMap.computeIfAbsent(eventTypeIdentifier, k -> new HandlerMethod(bean, method, eventType));
							subscribedQueryTypes.add(eventTypeIdentifier);
						}
					}
					/*
					 * Add to the set for initial subscription
					 */

					log.debug("Registered handler: {} in bean {} for event type: {}", methodName, beanName, eventTypeIdentifier);
				}
			});
		}
		log.debug("Finished scanning. Discovered {} unique event types with handlers.", subscribedEventTypes.size() + subscribedQueryTypes.size());
	}

	/**
	 * Dispatches the incoming event object to all registered handlers for its type.
	 *
	 * @param event The deserialised event object.
	 * @return Any response from a query handler, null otherwise.
	 */
	@Loggable(value = Loggable.TRACE, prepend = true)
	CompletableFuture<Object> dispatch(@NonNull Object event)
	{
		val eventClass = event.getClass();
		val eventAnnotation = eventClass.getAnnotation(Event.class);
		val queryAnnotation = eventClass.getAnnotation(Query.class);
		if (eventAnnotation == null && queryAnnotation == null)
		{
			log.warn("Received event of type {} without @Event or @Query annotation.", eventClass.getName());
		}
		val type = eventClass.getName();
		if (queryHandlerMap.containsKey(type))
		{
			val handler = queryHandlerMap.get(type);
			return CompletableFuture.supplyAsync(() ->
					dispatch(handler, type, event), queryExecutorService);
		}
		else
		{
			val handlers = eventHandlersMap.get(type);
			if (handlers == null || handlers.isEmpty())
			{
				log.debug("No handler registered for event type identifier: {}", type);
				return CompletableFuture.failedFuture(new IllegalStateException("No handler for event " + type));
			}

			handlers.forEach(handler ->
					eventExecutorService.submit(() ->
							dispatch(handler, type, event)));
			return CompletableFuture.completedFuture(Void.class);
		}
	}

	@Loggable(value = Loggable.TRACE, prepend = true)
	private static Object dispatch(HandlerMethod handlerMethod, String eventType, Object event)
	{
		val method = handlerMethod.method();
		val methodName = method.getName();
		val bean = handlerMethod.bean();
		try
		{
			/*
			 * Ensure the event type matches the handler's expected parameter type at runtime
			 */
			if (handlerMethod.expectedType().isInstance(event))
			{
				log.debug("Dispatched {} to handler method {}", eventType, methodName);
				val response = method.invoke(bean, event);
				val returnType = method.getReturnType();
				if (!returnType.equals(Void.TYPE))
				{
					log.debug("Received response from handler: {}", response);
				}
				return response;
			}
			else
			{
				/*
				 * This shouldn't happen.
				 */
				val expected = handlerMethod.expectedType().getName();
				log.warn("Handler method {} expected event type {} but received {}. Skipping invocation.",
						methodName,
						expected,
						eventType);
				return new QueryException(eventType, IllegalArgumentException.class.getName(), "Handler %s expected %s but was given %s".formatted(methodName, expected, eventType));
			}
		}
		catch (IllegalAccessException e)
		{
			val cause = e.getClass().getName();
			val message = e.getMessage();
			log.error("Error invoking event handler {}.{} for event type {}: [{}] {}",
					bean.getClass().getName(),
					methodName,
					eventType,
					cause,
					message,
					e);
			return new QueryException(eventType, cause, message);
		}
		catch (InvocationTargetException e)
		{
			val cause = e.getCause();
			val type = cause.getClass().getName();
			val message = cause.getMessage();
			log.debug("Responding exceptionally due to {} : {}", type, message, cause);
			/*
			 * For queries this will be returned to where the query was issued,
			 * and for events it will just be ignored.
			 */
			return new QueryException(eventType, type, message);
		}
	}

	/**
	 * Helper class to store the bean instance and its handler method.
	 */
	private record HandlerMethod(Object bean, Method method, Class<?> expectedType)
	{
	}
}
