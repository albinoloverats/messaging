module net.albinoloverats.messaging.server
{
	requires net.albinoloverats.messaging.common;
	requires spring.boot;
	requires spring.boot.autoconfigure;
	requires spring.context;
	requires org.slf4j;
	requires micrometer.core;
	requires jcabi.aspects;
	requires tools.jackson.core;
	requires tools.jackson.databind;
	requires tools.jackson.dataformat.yaml;
	requires org.apache.commons.lang3;
	requires org.bouncycastle.pkix;
	requires jakarta.annotation;

	requires static lombok;
}
