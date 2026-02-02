package net.albinoloverats.messaging.test.client;

import net.albinoloverats.messaging.test.matchers.Matcher;

import java.util.function.Function;

record QueryStub<Q, R>(Matcher<Q> matcher, Function<Q, R> responder)
{
}
