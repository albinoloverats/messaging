module net.albinoloverats.messaging.test
{
	requires transitive net.albinoloverats.messaging.client;

	requires spring.beans;
	requires spring.boot;
	requires spring.boot.autoconfigure;
	requires spring.context;
	requires spring.core;
	requires spring.test;
	requires org.slf4j;
	requires jakarta.annotation;
	requires com.fasterxml.jackson.annotation;
	requires tools.jackson.core;
	requires org.apache.commons.lang3;

	requires static lombok;

	exports net.albinoloverats.messaging.test;
	exports net.albinoloverats.messaging.test.annotations;
	exports net.albinoloverats.messaging.test.matchers;
}
