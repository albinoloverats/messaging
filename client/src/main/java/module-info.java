module net.albinoloverats.messaging.client
{
	requires transitive net.albinoloverats.messaging.common;
	requires transitive org.slf4j;

	requires com.fasterxml.jackson.annotation;
	requires tools.jackson.core;
	requires org.apache.commons.lang3;
	requires org.bouncycastle.pkix;
	requires jakarta.annotation;

	requires static spring.beans;
	requires static spring.boot;
	requires static spring.boot.autoconfigure;
	requires static spring.context;
	requires static spring.core;
	requires static lombok;
	requires static micrometer.core;
	requires static jcabi.aspects;

	exports net.albinoloverats.messaging.client;
}
