package net.albinoloverats.messaging.client.exceptions;

import java.io.IOException;

/**
 * Connection lost.
 */
final public class ConnectionLostException extends IOException
{
	/**
	 * Constructor.
	 *
	 * @param message Connection lost message.
	 */
	public ConnectionLostException(String message)
	{
		super(message);
	}
}
