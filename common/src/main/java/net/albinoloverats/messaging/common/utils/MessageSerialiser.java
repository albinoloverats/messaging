package net.albinoloverats.messaging.common.utils;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.io.JsonEOFException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.MapperConfig;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import net.albinoloverats.messaging.common.annotations.Event;
import net.albinoloverats.messaging.common.annotations.Query;
import net.albinoloverats.messaging.common.annotations.Response;
import net.albinoloverats.messaging.common.exceptions.InvalidMessage;
import net.albinoloverats.messaging.common.messages.MessageType;
import org.apache.commons.lang3.tuple.Triple;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;

import java.lang.annotation.Annotation;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Message serialise for events, queries, and responses, into JSNO using
 * Jackson, for network IO.
 */
@UtilityClass
@Slf4j
public class MessageSerialiser
{
	private static final Set<Class<? extends Annotation>> supportedAnnotations = Set.of(Event.class, Query.class, Response.class);
	/*
	 * Make ObjectMapper non-final and allow it to be configured after static block
	 */
	private static final ObjectMapper simpleMapper = new ObjectMapper();
	/*
	 * Make ObjectMapper non-final and allow it to be configured after static block
	 */
	private static final ObjectMapper objectMapper = new ObjectMapper();
	/*
	 * The base package for eventData classes, will be determined dynamically
	 */
	private static Set<String> baseEventScanPackage = Collections.emptySet();
	/*
	 * Will be initialized dynamically
	 */
	private static Pattern trustedPackagePattern;

	/*
	 * Initial static configuration (common to all instances/scans)
	 */
	static
	{
		objectMapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
		objectMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
		objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		objectMapper.configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false);
		objectMapper.configure(DeserializationFeature.FAIL_ON_UNRESOLVED_OBJECT_IDS, false);

		val typeValidator = new PolymorphicTypeValidator()
		{
			@Override
			public Validity validateBaseType(MapperConfig<?> config, JavaType baseType)
			{
				if (baseType.getRawClass().equals(java.util.Optional.class))
				{
					return Validity.ALLOWED;
				}
				return Validity.INDETERMINATE;
			}

			@Override
			public Validity validateSubClassName(MapperConfig<?> config, JavaType baseType, String subClassName)
			{
				/*
				 * This now relies on trustedPackagePattern being set up by the Spring config
				 */
				if (trustedPackagePattern != null && trustedPackagePattern.matcher(subClassName).matches())
				{
					return Validity.ALLOWED;
				}
				if (baseType.isArrayType() && subClassName.equals("[B"))
				{
					return Validity.ALLOWED;
				}
				log.warn("Denied deserialisation for untrusted class: {}", subClassName);
				return Validity.DENIED;
			}

			@Override
			public Validity validateSubType(MapperConfig<?> config, JavaType baseType, JavaType subType)
			{
				return validateSubClassName(config, baseType, subType.getRawClass().getName());
			}
		};
		val typeResolver = new ObjectMapper.DefaultTypeResolverBuilder(ObjectMapper.DefaultTyping.EVERYTHING, typeValidator)
		{
			@Override
			public boolean useForType(JavaType type)
			{
				if (type.getRawClass().equals(Optional.class))
				{
					return false;
				}
				return super.useForType(type);
			}
		}
				.init(JsonTypeInfo.Id.CLASS, null)
				.inclusion(JsonTypeInfo.As.WRAPPER_ARRAY);

		objectMapper.registerModule(new Jdk8Module());
		objectMapper.registerModule(new JavaTimeModule());

		objectMapper.setDefaultTyping(typeResolver);
	}

	/**
	 * Initialises the base package for eventData scanning and sets up the
	 * PolymorphicTypeValidator's pattern. This method must be called by a
	 * Spring component during application startup.
	 *
	 * @param basePackages The root packages to scan for @Event annotated classes.
	 */
	public static void setBaseScanPackagesAndInitValidator(Set<String> basePackages)
	{
		if (!baseEventScanPackage.isEmpty())
		{
			log.warn("MessageSerialiser.setBaseScanPackageAndInitValidator called multiple times. Ignoring subsequent calls.");
			return;
		}
		if (basePackages == null || basePackages.isEmpty())
		{
			log.error("No base packages provided for @Event class scanning. Event deserialisation might fail.");
			return;
		}
		baseEventScanPackage = new HashSet<>(basePackages);
		log.debug("MessageSerialiser configured to scan and trust base packages: {}", baseEventScanPackage);

		val combinedPattern = baseEventScanPackage.stream()
				.map(p -> "^" + Pattern.quote(p) + "(\\..*|$)$")
				.collect(Collectors.joining("|"));
		trustedPackagePattern = Pattern.compile(combinedPattern);
		/*
		 * Now perform the Reflections scan and register subtypes
		 */
		val urls = new HashSet<URL>();
		for (val packageName : baseEventScanPackage)
		{
			urls.addAll(ClasspathHelper.forPackage(packageName));
		}
		val reflections = new Reflections(new ConfigurationBuilder()
				.setUrls(urls)
				.setScanners(Scanners.TypesAnnotated)
				.filterInputsBy(input -> input != null && !input.contains("$"))
		);

		for (val supported : supportedAnnotations)
		{
			val messageTypes = reflections.getTypesAnnotatedWith(supported);
			for (val type : messageTypes)
			{
				val typeName = type.getName();
				var checkDouble = true;
				for (val check : supportedAnnotations)
				{
					if (!type.isAnnotationPresent(check))
					{
						checkDouble = false;
						break;
					}
				}
				if (checkDouble)
				{
					log.warn("Class {} cannot be both an @Event and a @Response. Ignoring.", typeName);
				}
				else
				{
					val module = new SimpleModule(typeName);
					module.registerSubtypes(new NamedType(type, typeName));
					objectMapper.registerModule(module);
					log.debug("Registered {}: {} with identifier: {}", supported.getSimpleName(), type.getName(), typeName);
				}
			}
		}
	}

	/**
	 * Serialise the given event into JSON with the addition of the ID and
	 * message type.
	 *
	 * @param event The event to serialise.
	 * @param id    The event ID.
	 * @param type  The message type.
	 * @return A {@link ByteBuffer} representation of the event as JSON.
	 * @throws JsonProcessingException Thrown by Jackson's object mapper if the event could not be serialised.
	 */
	public static <O> ByteBuffer serialiseEvent(O event, UUID id, MessageType type) throws JsonProcessingException
	{
		val json = objectMapper.writeValueAsString(event);
		val buffer = stringToBuffer(json);
		var string = bufferToString(buffer);
		val array = (ArrayNode)simpleMapper.readTree(string);
		array.add(id.toString());
		array.add(type.name());
		string = simpleMapper.writeValueAsString(array);
		return stringToBuffer(string);
	}

	/**
	 * Deserialise the given {@link ByteBuffer} from its JSON to the original object.
	 *
	 * @param buffer The JSON buffer to deserialise.
	 * @return The original event.
	 * @throws JsonProcessingException Thrown by Jackson's object mapper if the event could not be deserialised.
	 */
	public static <O> O deserialiseMessage(ByteBuffer buffer) throws JsonProcessingException
	{
		val string = bufferToString(buffer);
		return objectMapper.readValue(string, new TypeReference<>()
		{
		});
	}

	/**
	 * Retrieve the 3 constituent parts of a serialised message: JSON payload, ID, message type.
	 *
	 * @param buffer The raw {@link ByteBuffer} from the network.
	 * @return The JSON buffer, event ID, and message type.
	 * @throws JsonProcessingException Thrown by Jackson's object mapper if the event could not be deserialised.
	 * @throws InvalidMessage          Thrown if the payload does not contain the expected/required ID and type.
	 */
	public static Triple<ByteBuffer, UUID, MessageType> separateMessage(ByteBuffer buffer) throws JsonProcessingException, InvalidMessage
	{
		var string = bufferToString(buffer);
		val json = (ArrayNode)simpleMapper.readTree(string);
		// 0 is type, 1 is actual event data, 2 should be ID
		UUID id = null;
		var type = MessageType.EVENT;
		if (json.size() == 4)
		{
			try
			{
				id = UUID.fromString(json.get(2).asText());
			}
			catch (IllegalArgumentException e)
			{
				throw InvalidMessage.missingId();
			}
			try
			{
				type = MessageType.fromString(json.get(3).asText());
			}
			catch (IllegalArgumentException e)
			{
				throw InvalidMessage.missingType();
			}
			json.remove(3);
			json.remove(2);
		}
		else
		{
			throw InvalidMessage.invalidMessage();
		}
		string = simpleMapper.writeValueAsString(json);
		buffer = stringToBuffer(string);
		return Triple.of(buffer, id, type);
	}

	/**
	 * Get the event type for the given JSON {@link ByteBuffer} payload.
	 *
	 * @param buffer The JSON buffer.
	 * @return The event type.
	 * @throws JsonProcessingException Thrown by Jackson's object mapper if the event could not be deserialised.
	 */
	public static String findEventType(ByteBuffer buffer) throws JsonProcessingException
	{
		val string = bufferToString(buffer);
		val json = simpleMapper.readTree(string);
		val type = json.get(0);
		return type.asText();
	}

	private static ByteBuffer stringToBuffer(String string)
	{
		val bytes = string.getBytes(StandardCharsets.UTF_8);
		val buffer = ByteBuffer.allocate(Integer.BYTES + bytes.length);
		buffer.putInt(bytes.length);
		buffer.put(bytes);
		buffer.flip();
		return buffer;
	}

	/**
	 * Convert the given {@link ByteBuffer} to a JSON {@link String}.
	 *
	 * @param buffer The payload to parse.
	 * @return The JSON.
	 * @throws JsonEOFException Thrown if the payload do not contain a full JSON object.
	 */
	public static String bufferToString(ByteBuffer buffer) throws JsonEOFException
	{
		val length = buffer.getInt();
		if (buffer.remaining() < length)
		{
			throw new JsonEOFException(null, null, "Buffer does not contain full message data.");
		}
		val bytes = new byte[length];
		buffer.get(bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}
}
