module net.albinoloverats.messaging.server
{
	requires transitive net.albinoloverats.messaging.common;
	requires transitive org.slf4j;

	requires com.fasterxml.jackson.core;
	requires com.fasterxml.jackson.databind;
	requires com.fasterxml.jackson.dataformat.yaml;
	requires org.apache.commons.lang3;
	requires org.bouncycastle.pkix;
	requires jakarta.annotation;

	requires static spring.boot;
	requires static spring.boot.autoconfigure;
	requires static spring.context;
	requires static lombok;
	requires static micrometer.core;
	requires static jcabi.aspects;
}
