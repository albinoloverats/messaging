package net.albinoloverats.messaging.test.config;

import lombok.val;
import net.albinoloverats.messaging.test.annotations.MessagingDispatch;
import net.albinoloverats.messaging.test.annotations.MessagingNoDispatch;
import net.albinoloverats.messaging.test.client.TestMessagingClient;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;

@Order(Ordered.HIGHEST_PRECEDENCE)
public final class MessagingTestExecutionListener implements TestExecutionListener
{
	@Override
	public void beforeTestMethod(TestContext testContext)
	{
		val client = getClient(testContext);
		if (client == null)
		{
			return;
		}
		val withDispatch = resolveDispatch(testContext);
		client.setWithDispatch(withDispatch);
	}

	@Override
	public void afterTestMethod(TestContext testContext)
	{
		val client = getClient(testContext);
		if (client != null)
		{
			client.reset();
		}
	}

	private TestMessagingClient getClient(TestContext testContext)
	{
		if (testContext.hasApplicationContext())
		{
			val appContext = testContext.getApplicationContext();
			if (appContext.containsBean(TestMessagingClient.class.getName()))
			{
				return appContext.getBean(TestMessagingClient.class);
			}
			else if (appContext.containsBean("testMessagingClient"))
			{
				return ((TestMessagingClient)appContext.getBean("testMessagingClient"));
			}
		}
		return null;
	}

	private boolean resolveDispatch(TestContext context)
	{
		val method = context.getTestMethod();
		val clazz = context.getTestClass();
		// Method-level override
		if (hasAnnotation(method, MessagingDispatch.class))
		{
			return true;
		}
		if (hasAnnotation(method, MessagingNoDispatch.class))
		{
			return false;
		}
		// Class-level default
		if (hasAnnotation(clazz, MessagingDispatch.class))
		{
			return true;
		}
		if (hasAnnotation(clazz, MessagingNoDispatch.class))
		{
			return false;
		}
		return false;
	}

	private boolean hasAnnotation(AnnotatedElement element, Class<? extends Annotation> annotation)
	{
		return element != null && element.isAnnotationPresent(annotation);
	}
}
