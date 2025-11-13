package net.albinoloverats.messaging.common.utils;

import lombok.experimental.UtilityClass;

import java.time.Duration;

/**
 * Constant and default values.
 */
@UtilityClass
public class Constants
{
	/**
	 * Prefix for metrics.
	 */
	public static final String MESSAGING = "messaging";

	/**
	 * Host value to indicate auto-discovery.
	 */
	public static final String AUTO_DISCOVERY = "auto";
	/**
	 * Host value to disable any discovery; single server mode.
	 */
	public static final String NO_DISCOVERY = "none";
	/**
	 * Port for incoming server connections.
	 */
	public static final int PORT = 2413;

	/**
	 * Default security provider: Bouncy Castle.
	 */
	public static final String SECURITY_PROVIDER = "BC";
	/**
	 * Ephemeral key algorithm.
	 */
	public static final String KEY_ALGORITHM = "RSA";
	/**
	 * Ephemeral key-store type.
	 */
	public static final String KEY_STORE_TYPE = "JKS";
	/**
	 * Ephemeral trust store type.
	 */
	public static final String KEY_TRUST_TYPE = "SunX509";
	/**
	 * Ephemeral signing algorithm.
	 */
	public static final String SIGNING_ALGORITHM = "SHA256WithRSA";
	/**
	 * Ephemeral protocol.
	 */
	public static final String CONTEXT_PROTOCOL = "TLS";

	/**
	 * Incoming/read buffer size.
	 * <p>
	 * 128MB was chosen because it's roughly 125,000 1KB messages, and
	 * substantially more than the 100,000/s that can be achieved in ideal
	 * test conditions (real world usage may vary).
	 */
	public static final int READ_BUFFER_SIZE = 128 * 1024 * 1024;
	/**
	 * Auto-discovery network buffer size.
	 */
	public static final int DISCOVERY_BUFFER_SIZE = 128;
	/**
	 * SSL/TLS packet size.
	 */
	public static final int TLS_BUFFER_SIZE = 16 * 1024;

	/**
	 * Outbound message queue size.
	 */
	public static final int QUEUE_SIZE = 128 * 1024;

	/**
	 * Duration of 1 second.
	 */
	public static final Duration ONE_SECOND = Duration.ofSeconds(1);
	/**
	 * Duration of 5 seconds.
	 */
	public static final Duration FIVE_SECONDS = Duration.ofSeconds(5);
	/**
	 * Duration of 10 seconds.
	 */
	public static final Duration TEN_SECONDS = Duration.ofSeconds(10);
	/**
	 * Duration of 1 minute.
	 */
	public static final Duration ONE_MINUTE = Duration.ofMinutes(1);
	/**
	 * Duration of 1 year.
	 */
	public static final Duration ONE_YEAR = Duration.ofDays(365);
}
