package net.albinoloverats.messaging.common.exceptions;

/**
 * Invalid/unknown message between server instances or client-server.
 */
public final class InvalidMessage extends Exception
{
	private static final String INVALID_MESSAGE = "Missing required ID and/or message type";
	private static final String MISSING_ID = "Missing required ID";
	private static final String MISSING_TYPE = "Missing required message type";

	private InvalidMessage(String message)
	{
		super(message);
	}

	public static InvalidMessage invalidMessage()
	{
		return new InvalidMessage(INVALID_MESSAGE);
	}

	public static InvalidMessage missingId()
	{
		return new InvalidMessage(MISSING_ID);
	}

	public static InvalidMessage missingType()
	{
		return new InvalidMessage(MISSING_TYPE);
	}
}
