package net.albinoloverats.messaging.common.utils;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import net.albinoloverats.messaging.common.annotations.Event;
import net.albinoloverats.messaging.common.annotations.Query;
import net.albinoloverats.messaging.common.annotations.Response;
import net.albinoloverats.messaging.common.exceptions.InvalidMessage;
import net.albinoloverats.messaging.common.messages.MessageType;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DatabindContext;
import tools.jackson.databind.DefaultTyping;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.NamedType;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;
import tools.jackson.databind.jsontype.impl.DefaultTypeResolverBuilder;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.node.ArrayNode;

import java.io.EOFException;
import java.lang.annotation.Annotation;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
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
	private static ObjectMapper objectMapper;

	private static boolean initialised = false;

	/*
	 * Initial static configuration (common to all instances/scans)
	 */
	private static void initialiseMapper(@NonNull Pattern trustedPackagePattern, @NonNull Set<JacksonModule> modules)
	{
		val typeValidator = new PolymorphicTypeValidator()
		{
			@Override
			public Validity validateBaseType(DatabindContext context, JavaType baseType)
			{
				if (baseType.getRawClass().equals(java.util.Optional.class))
				{
					return Validity.ALLOWED;
				}
				return Validity.INDETERMINATE;
			}

			@Override
			public Validity validateSubClassName(DatabindContext context, JavaType baseType, String subClassName)
			{
				/*
				 * This now relies on trustedPackagePattern being set up by the Spring config
				 */
				if (trustedPackagePattern.matcher(subClassName).matches())
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
			public Validity validateSubType(DatabindContext context, JavaType baseType, JavaType subType)
			{
				return validateSubClassName(context, baseType, subType.getRawClass().getName());
			}
		};
		val typeResolver = new DefaultTypeResolverBuilder(typeValidator, DefaultTyping.NON_FINAL_AND_ENUMS, JsonTypeInfo.As.WRAPPER_ARRAY)
		{
			@Override
			public boolean useForType(JavaType type)
			{
				val rawClass = type.getRawClass();
				if (rawClass.equals(Optional.class) || type.isPrimitive())
				{
					return false;
				}
				if (rawClass.getCanonicalName().startsWith("java."))
				{
					return super.useForType(type);
				}
				return true;
			}
		};
		objectMapper = JsonMapper.builder()
				.changeDefaultVisibility(checker -> checker.withFieldVisibility(JsonAutoDetect.Visibility.ANY))
				.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
				.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
				.disable(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE)
				.disable(DeserializationFeature.FAIL_ON_UNRESOLVED_OBJECT_IDS)
				.addModules(modules)
				.setDefaultTyping(typeResolver)
				.build();
	}

	private static Set<JacksonModule> initialiseModules(@NonNull Reflections reflections)
	{
		val modules = new HashSet<JacksonModule>();
		for (val supported : supportedAnnotations)
		{
			val messageTypes = reflections.getTypesAnnotatedWith(supported);
			for (val type : messageTypes)
			{
				val typeName = type.getName();
				val checkDouble = supportedAnnotations.stream()
						.noneMatch(Predicate.not(type::isAnnotationPresent));
				if (checkDouble)
				{
					log.warn("Class {} cannot be both an @Event and a @Response. Ignoring.", typeName);
				}
				else
				{
					val module = new SimpleModule(typeName);
					module.registerSubtypes(new NamedType(type, typeName));
					modules.add(module);
					log.debug("Registered {}: {} with identifier: {}", supported.getSimpleName(), type.getName(), typeName);
				}
			}
		}
		return modules;
	}

	private Pair<Pattern, Reflections> scanPackages(@NonNull Set<String> basePackages)
	{
		if (basePackages.isEmpty())
		{
			log.warn("No base packages provided for @Event class scanning. Event deserialisation might fail.");
		}
		log.debug("MessageSerialiser configured to scan and trust base packages: {}", basePackages);

		val combinedPattern = basePackages.stream()
				.map(p -> "^" + Pattern.quote(p) + "(\\..*|$)$")
				.collect(Collectors.joining("|"));
		val trustedPackagePattern = Pattern.compile(combinedPattern);
		/*
		 * Now perform the Reflections scan and register subtypes
		 */
		val urls = basePackages.stream()
				.map(ClasspathHelper::forPackage)
				.flatMap(Collection::stream)
				.collect(Collectors.toSet());
		return Pair.of(
				trustedPackagePattern,
				new Reflections(new ConfigurationBuilder()
						.setUrls(urls)
						.setScanners(Scanners.TypesAnnotated)
						.filterInputsBy(input -> input != null && !input.contains("$")))
		);
	}

	/**
	 * Initialises the base package for eventData scanning and sets up the
	 * PolymorphicTypeValidator's pattern. This method must be called by a
	 * Spring component during application startup.
	 *
	 * @param basePackages The root packages to scan for @Event annotated classes.
	 */
	public static void initialise(@NonNull Set<String> basePackages)
	{
		if (initialised)
		{
			log.warn("MessageSerialiser.initialise called multiple times. Ignoring subsequent calls.");
			return;
		}
		val pair = scanPackages(basePackages);
		val trustedPackagePattern = pair.getLeft();
		val reflections = pair.getRight();
		val modules = initialiseModules(reflections);
		initialiseMapper(trustedPackagePattern, modules);
		initialised = true;
	}

	/**
	 * Serialise the given event into JSON with the addition of the ID and
	 * message type.
	 *
	 * @param event The event to serialise.
	 * @param id    The event ID.
	 * @param type  The message type.
	 * @return A {@link ByteBuffer} representation of the event as JSON.
	 * @throws JacksonException Thrown by Jackson's object mapper if the event could not be serialised.
	 */
	public static <O> ByteBuffer serialiseEvent(@NonNull O event, @NonNull UUID id, @NonNull MessageType type) throws JacksonException
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
	 * @throws JacksonException Thrown by Jackson's object mapper if the event could not be deserialised.
	 */
	public static <O> O deserialiseEvent(@NonNull ByteBuffer buffer) throws JacksonException, InvalidMessage
	{
		var string = bufferToString(buffer);
		buffer.rewind();
		val json = (ArrayNode)simpleMapper.readTree(string);
		if (json.size() != 4)
		{
			throw InvalidMessage.invalidMessage();
		}
		json.remove(3);
		json.remove(2);
		string = simpleMapper.writeValueAsString(json);
		return objectMapper.readValue(string, new TypeReference<>()
		{
		});
	}

	public static String extractEvent(@NonNull ByteBuffer buffer) throws JacksonException, InvalidMessage
	{
		val string = bufferToString(buffer);
		buffer.rewind();
		val json = (ArrayNode)simpleMapper.readTree(string);
		if (json.size() != 4)
		{
			throw InvalidMessage.invalidMessage();
		}
		return simpleMapper.writeValueAsString(json.get(1));
	}

	/**
	 * Retrieve the 3 constituent parts of a serialised message: JSON payload, ID, message type.
	 *
	 * @param buffer The raw {@link ByteBuffer} from the network.
	 * @return The JSON buffer, event ID, and message type.
	 * @throws JacksonException Thrown by Jackson's object mapper if the event could not be deserialised.
	 * @throws InvalidMessage   Thrown if the payload does not contain the expected/required ID and type.
	 */
	public static Triple<String, UUID, MessageType> extractMetadata(@NonNull ByteBuffer buffer) throws JacksonException, InvalidMessage
	{
		val string = bufferToString(buffer);
		buffer.rewind();
		val json = (ArrayNode)simpleMapper.readTree(string);
		if (json.size() != 4)
		{
			throw InvalidMessage.invalidMessage();
		}
		// 0 is original class, 1 is actual event data, 2 should be ID, 3 is the type (event/query/response)

		val original = json.get(0).asString();
		UUID id;
		try
		{
			id = UUID.fromString(json.get(2).asString());
		}
		catch (IllegalArgumentException e)
		{
			throw InvalidMessage.missingId();
		}
		MessageType type;
		try
		{
			type = MessageType.fromString(json.get(3).asString());
		}
		catch (IllegalArgumentException e)
		{
			throw InvalidMessage.missingType();
		}
		return Triple.of(original, id, type);
	}

	private static ByteBuffer stringToBuffer(@NonNull String string)
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
	 * @throws JacksonException Thrown if the payload do not contain a full JSON object.
	 */
	private static String bufferToString(@NonNull ByteBuffer buffer) throws JacksonException
	{
		val length = buffer.getInt();
		if (buffer.remaining() < length)
		{
			throw JacksonIOException.construct(new EOFException("Buffer does not contain full message data."));
		}
		val bytes = new byte[length];
		buffer.get(bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}
}
