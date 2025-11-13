package net.albinoloverats.messaging.common.functions;

import java.util.function.Supplier;

/**
 * Functional interface to allow lambda expression to throw an exception; the
 * original exceptions gets wrapped in a {@link RuntimeException}.
 *
 * @param <T> Response type.
 * @param <E> Exception type.
 */
@FunctionalInterface
public interface ThrowingSupplier<T, E extends Exception>
{
	T get() throws E;

	static <T, E extends Exception> Supplier<T> withException(ThrowingSupplier<T, E> supplier)
	{
		return () ->
		{
			try
			{
				return supplier.get();
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		};
	}
}
