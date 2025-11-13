package net.albinoloverats.messaging.client.config;

import lombok.val;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

final public class OnMessagingEnabledCondition implements Condition
{
	@Override
	public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata)
	{
		val enabled = context.getEnvironment()
				.getProperty("messaging.enabled");
		return !"false".equalsIgnoreCase(enabled); // enabled unless explicitly false
	}
}
