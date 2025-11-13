package net.albinoloverats.messaging.client.utils;

import lombok.experimental.UtilityClass;
import lombok.val;

import java.lang.reflect.InvocationTargetException;

/**
 * Utility class to recreate (some) exceptions after serialisation, transfer, deserialisation.
 */
@UtilityClass
final public class ExceptionReplicator
{
	/**
	 * Attempts to create an instance of an Exception class given its
	 * full name and a message. It specifically looks for a constructor
	 * that accepts a single String argument (the message).
	 *
	 * @param className The full class name of the Exception.
	 * @param message   The message to pass to the Exception's constructor.
	 * @return A new instance of the specified Exception, or a generic RuntimeException if instantiation fails.
	 */
	public static Throwable createException(String className, String message)
	{
		try
		{
			val exceptionClass = Class.forName(className);
			if (!Throwable.class.isAssignableFrom(exceptionClass))
			{
				return new IllegalArgumentException("Class " + className + " is not an Exception.");
			}
			val constructor = exceptionClass.getConstructor(String.class);
			val instance = constructor.newInstance(message);
			return (Throwable)instance;
		}
		catch (ClassNotFoundException e)
		{
			return new RuntimeException("Original exception class not found: " + className, e);
		}
		catch (NoSuchMethodException e)
		{
			return new RuntimeException("Original exception class does not have a String message constructor: " + className, e);
		}
		catch (InstantiationException e)
		{
			return new RuntimeException("Failed to instantiate original exception class: " + className, e);
		}
		catch (IllegalAccessException e)
		{
			return new RuntimeException("Access denied to constructor for exception class: " + className, e);
		}
		catch (InvocationTargetException e)
		{
			return new RuntimeException("Constructor for exception class threw an exception: " + className, e.getTargetException());
		}
		catch (ClassCastException e)
		{
			return new RuntimeException("Original exception class cannot be cast to Throwable: " + className, e);
		}
	}
}
