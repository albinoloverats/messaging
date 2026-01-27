module net.albinoloverats.messaging.server
{
	requires transitive net.albinoloverats.messaging.common;
	requires transitive org.slf4j;

	requires tools.jackson.core;
	requires tools.jackson.databind;
	requires tools.jackson.dataformat.yaml;
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
