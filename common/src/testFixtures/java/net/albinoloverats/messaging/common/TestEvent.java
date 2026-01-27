package net.albinoloverats.messaging.common;


import net.albinoloverats.messaging.common.annotations.Event;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Event
public record TestEvent(UUID id,
                        String description,
                        int value,
                        List<Boolean> yesNo,
                        Map<String, String> more,
                        Instant at,
                        Optional<String> maybe,
                        Optional<TestEvent> another)
{
}
