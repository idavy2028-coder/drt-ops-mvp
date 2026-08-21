package com.idavy.drtops.domain.alarm;

import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/vehicle-alarms/events")
public class AlarmEventStreamController {
    private final AlarmEventStreamService stream;

    AlarmEventStreamController(AlarmEventStreamService stream) {
        this.stream = stream;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter subscribe(
            Authentication authentication,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        return stream.subscribe(actorId(authentication), lastEventId);
    }

    private static UUID actorId(Authentication authentication) {
        if (authentication == null) throw new VehicleAlarmAuthorizationException("vehicle alarm stream is forbidden");
        Object principal = authentication.getPrincipal();
        if (principal instanceof UUID actor) return actor;
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException exception) {
            throw new VehicleAlarmAuthorizationException("vehicle alarm stream is forbidden");
        }
    }
}
