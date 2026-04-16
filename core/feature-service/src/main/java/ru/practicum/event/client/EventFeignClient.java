package ru.practicum.event.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import ru.practicum.event.dto.EventShortDto;

import java.util.List;

@FeignClient(name = "event-service")
public interface EventFeignClient {

    @PostMapping("/events/internal/by-ids")
    List<EventShortDto> getEventsByIds(@RequestBody List<Long> ids);
}
