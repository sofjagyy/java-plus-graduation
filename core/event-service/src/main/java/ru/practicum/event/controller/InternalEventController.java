package ru.practicum.event.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import ru.practicum.event.dto.EventFullDto;
import ru.practicum.event.dto.EventShortDto;
import ru.practicum.event.service.EventService;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class InternalEventController {

    private final EventService eventService;

    @GetMapping("/events/{eventId}/internal")
    public EventFullDto getEventByIdInternal(@PathVariable Long eventId) {
        return eventService.getEventById(eventId);
    }

    @PostMapping("/events/internal/by-ids")
    public List<EventShortDto> getEventsByIds(@RequestBody List<Long> ids) {
        return eventService.getEventsByIds(ids);
    }
}
