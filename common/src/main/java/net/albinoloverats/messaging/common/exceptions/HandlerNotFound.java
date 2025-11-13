package net.albinoloverats.messaging.common.exceptions;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.albinoloverats.messaging.common.annotations.Response;

/**
 * Event/query handler not found.
 */
@RequiredArgsConstructor
@Getter
@Response
public class HandlerNotFound extends SerialisableException
{
	/**
	 * Original event/query type.
	 */
	@JsonProperty
	private final String type;

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String getMessage()
	{
		return "No handler found for " + type;
	}
}
