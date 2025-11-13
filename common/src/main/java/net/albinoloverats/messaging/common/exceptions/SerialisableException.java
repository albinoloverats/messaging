package net.albinoloverats.messaging.common.exceptions;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

/**
 * Could not serialise event or query object.
 */
@JsonAutoDetect(
		fieldVisibility = JsonAutoDetect.Visibility.NONE,    // Don't auto-detect fields
		getterVisibility = JsonAutoDetect.Visibility.NONE,   // Don't auto-detect getters
		isGetterVisibility = JsonAutoDetect.Visibility.NONE) // Don't auto-detect 'is' getters
public abstract class SerialisableException extends Exception
{
}
