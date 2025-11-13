package net.albinoloverats.messaging.client.exceptions;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import net.albinoloverats.messaging.common.annotations.Response;
import net.albinoloverats.messaging.common.exceptions.SerialisableException;

/**
 * Query has responded exceptionally.
 */
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Response
final public class QueryException extends SerialisableException
{
	/**
	 * The original query type (class).
	 */
	@JsonProperty
	private String queryType;
	/**
	 * The original exception that the query handler responded with.
	 */
	@JsonProperty
	private String originalCause;
	/**
	 * The message from the original exception.
	 */
	@JsonProperty
	private String originalMessage;
}
