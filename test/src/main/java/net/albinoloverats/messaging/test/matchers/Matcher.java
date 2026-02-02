package net.albinoloverats.messaging.test.matchers;

@FunctionalInterface
public interface Matcher<Q>
{
	boolean matches(Q value);
}
