module net.albinoloverats.messaging.common
{
	requires transitive org.slf4j;
	requires transitive org.bouncycastle.pkix;
	requires transitive org.bouncycastle.provider;

	requires com.fasterxml.jackson.annotation;
	requires tools.jackson.core;
	requires tools.jackson.databind;
	requires org.apache.commons.lang3;

	requires static spring.boot;
	requires static lombok;
	requires static micrometer.core;
	requires static org.reflections;
	requires static jcabi.aspects;

	exports net.albinoloverats.messaging.common to net.albinoloverats.messaging.client, net.albinoloverats.messaging.server;
	exports net.albinoloverats.messaging.common.annotations to net.albinoloverats.messaging.client, net.albinoloverats.messaging.server;
	exports net.albinoloverats.messaging.common.exceptions to net.albinoloverats.messaging.client, net.albinoloverats.messaging.server;
	exports net.albinoloverats.messaging.common.functions to net.albinoloverats.messaging.client, net.albinoloverats.messaging.server;
	exports net.albinoloverats.messaging.common.config to net.albinoloverats.messaging.client, net.albinoloverats.messaging.server;
	exports net.albinoloverats.messaging.common.messages to net.albinoloverats.messaging.client, net.albinoloverats.messaging.server;
	exports net.albinoloverats.messaging.common.metrics to net.albinoloverats.messaging.client, net.albinoloverats.messaging.server;
	exports net.albinoloverats.messaging.common.security to net.albinoloverats.messaging.client, net.albinoloverats.messaging.server;
	exports net.albinoloverats.messaging.common.utils to net.albinoloverats.messaging.client, net.albinoloverats.messaging.server;
}
