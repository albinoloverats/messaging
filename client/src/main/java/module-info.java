module net.albinoloverats.messaging.client
{
	requires transitive net.albinoloverats.messaging.common;

	requires spring.beans;
	requires spring.boot;
	requires spring.boot.autoconfigure;
	requires spring.context;
	requires spring.core;
	requires org.slf4j;
	requires micrometer.core;
	requires jcabi.aspects;
	requires com.fasterxml.jackson.annotation;
	requires tools.jackson.core;
	requires org.apache.commons.lang3;
	requires org.bouncycastle.pkix;
	requires jakarta.annotation;

	requires static lombok;

	exports net.albinoloverats.messaging.client;
	exports net.albinoloverats.messaging.client.client to net.albinoloverats.messaging.test;
	exports net.albinoloverats.messaging.client.config to net.albinoloverats.messaging.test;
}
