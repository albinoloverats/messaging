package net.albinoloverats.messaging.test.matchers;

import lombok.experimental.UtilityClass;

import java.util.Objects;
import java.util.function.Predicate;

@UtilityClass
public final class Matchers
{
	/**
	 * Create a Matcher for a query of the given class.
	 *
	 * @param type The class of the query to match.
	 * @return A class Matcher.
	 */
	public static <Q> Matcher<Q> of(Class<Q> type)
	{
		return value -> value != null && type.isAssignableFrom(value.getClass());
	}

	/**
	 * Create a Matcher for a query that is equal; uses Objects.equals()
	 *
	 * @param expected The value of the query to match.
	 * @return A value Matcher.
	 */
	public static <Q> Matcher<Q> exactly(Q expected)
	{
		return value -> Objects.equals(expected, value);
	}

	/**
	 * Create a Matcher for a query that evaluates the given predicate.
	 *
	 * @param predicate The predicate to evaluate.
	 * @return A predicate Matcher.
	 */
	public static <Q> Matcher<Q> matching(Predicate<Q> predicate)
	{
		return predicate::test;
	}

	/**
	 * Create a Matcher for anything.
	 *
	 * @return A Matcher that matches anything.
	 */
	public static <Q> Matcher<Q> anyValue()
	{
		return value -> true;
	}
}
