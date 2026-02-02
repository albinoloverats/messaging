module net.albinoloverats.messaging.common
{
	requires transitive org.bouncycastle.pkix;
	requires transitive org.bouncycastle.provider;

	requires spring.boot;
	requires org.slf4j;
	requires micrometer.core;
	requires jcabi.aspects;
	requires org.reflections;
	requires com.fasterxml.jackson.annotation;
	requires tools.jackson.core;
	requires tools.jackson.databind;
	requires org.apache.commons.lang3;

	requires static lombok;

	exports net.albinoloverats.messaging.common to net.albinoloverats.messaging.client, net.albinoloverats.messaging.server;
	exports net.albinoloverats.messaging.common.annotations;
	exports net.albinoloverats.messaging.common.exceptions;
	exports net.albinoloverats.messaging.common.functions to net.albinoloverats.messaging.client, net.albinoloverats.messaging.server;
	exports net.albinoloverats.messaging.common.config to net.albinoloverats.messaging.client, net.albinoloverats.messaging.server;
	exports net.albinoloverats.messaging.common.messages to net.albinoloverats.messaging.client, net.albinoloverats.messaging.server, net.albinoloverats.messaging.test;
	exports net.albinoloverats.messaging.common.metrics to net.albinoloverats.messaging.client, net.albinoloverats.messaging.server;
	exports net.albinoloverats.messaging.common.security to net.albinoloverats.messaging.client, net.albinoloverats.messaging.server;
	exports net.albinoloverats.messaging.common.utils to net.albinoloverats.messaging.client, net.albinoloverats.messaging.server, net.albinoloverats.messaging.test;
}
