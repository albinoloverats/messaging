package net.albinoloverats.messaging.server.utils;

import com.jcabi.aspects.Loggable;
import lombok.val;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

import static net.albinoloverats.messaging.common.functions.ThrowingSupplier.withException;

final public class ConsistentHashRing
{
	private static final int VIRTUAL_NODES = 128;

	private final Map<String, ConcurrentSkipListMap<Long, UUID>> rings = new ConcurrentHashMap<>();
	private final ThreadLocal<MessageDigest> threadLocalDigest = ThreadLocal.withInitial(withException(() -> MessageDigest.getInstance("SHA-256")));

	@Loggable(value = Loggable.TRACE, prepend = true)
	synchronized public void updateServers(Map<String, Set<UUID>> serverCapabilities)
	{
		serverCapabilities.forEach((type, serverIds) ->
		{
			val ring = rings.getOrDefault(type, new ConcurrentSkipListMap<>());
			ring.clear();
			for (int i = 0; i < VIRTUAL_NODES; i++)
			{
				for (val serverId : serverIds)
				{
					val hash = hash(serverId.toString() + "-virtual-" + i);
					ring.put(hash, serverId);
				}
			}
			rings.put(type, ring);
		});
	}

	@Loggable(value = Loggable.TRACE, prepend = true)
	public UUID getServerForKey(String type, UUID id, String service)
	{
		if (rings.isEmpty() || !rings.containsKey(type))
		{
			return null;
		}
		val key = type + "::" + id.toString() + "::" + service;
		val ring = rings.get(type);
		val hash = hash(key);
		val entry = ring.ceilingEntry(hash);
		return entry == null ? ring.firstEntry().getValue() : entry.getValue();
	}

	private long hash(String key)
	{
		val digest = threadLocalDigest.get();
		val bytes = digest.digest(key.getBytes(StandardCharsets.UTF_8));
		return ((long)(bytes[0] & 0xFF) << 56)
				| ((long)(bytes[1] & 0xFF) << 48)
				| ((long)(bytes[2] & 0xFF) << 40)
				| ((long)(bytes[3] & 0xFF) << 32)
				| ((long)(bytes[4] & 0xFF) << 24)
				| ((long)(bytes[5] & 0xFF) << 16)
				| ((long)(bytes[6] & 0xFF) << 8)
				| ((long)(bytes[7] & 0xFF));
	}
}
