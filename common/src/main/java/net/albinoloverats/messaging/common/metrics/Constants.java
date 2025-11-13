package net.albinoloverats.messaging.common.metrics;

import lombok.experimental.UtilityClass;

import static net.albinoloverats.messaging.common.utils.Constants.MESSAGING;

/**
 * Constants to use for data metric gathering.
 */
@UtilityClass
public class Constants
{
	public static final String EVENT = "event";
	public static final String QUERY = "query";

	public static final String CONNECTIONS = MESSAGING + ".connections";
	public static final String CONNECTION_TYPE = "type";
	public static final String PEERS = "peers";
	public static final String CLIENTS = "clients";

	private static final String PENDING = "pending";
	private static final String QUEUE = "queue";
	private static final String DURATION = "duration";

	public static final String QUERY_PENDING = MESSAGING + "." + QUERY + "." + PENDING;
	public static final String QUERY_DURATION = MESSAGING + "." + QUERY + "." + DURATION;
	public static final String OUTBOUND_QUEUE = MESSAGING + ".outbound." + QUEUE;
}
