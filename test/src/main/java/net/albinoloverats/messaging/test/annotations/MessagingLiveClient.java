package net.albinoloverats.messaging.test.annotations;

import org.springframework.test.context.TestPropertySource;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to indicate, and set up, using the default, live client
 * and server for testing. Allows for more integration-style testing if
 * you have such a test environment. Disables
 * {@link net.albinoloverats.messaging.test.TestMessagingHarness}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@TestPropertySource(properties = "messaging.test.live=true")
public @interface MessagingLiveClient
{
}
