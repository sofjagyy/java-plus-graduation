package ru.practicum.event.service;

import ru.practicum.event.dto.*;

import java.util.List;

public interface EventService {
    List<EventFullDto> getEventsAdmin(EventAdminFilterParams params);

    EventFullDto updateEventAdmin(Long eventId, UpdateEventAdminDto request);

    List<EventShortDto> getEventsPublic(EventPublicFilterParams params);

    EventFullDto getEventPublic(Long id, Long userId);

    List<EventShortDto> getEventsUser(Long userId, int from, int size);

    EventFullDto createEventUser(Long userId, NewEventDto newEventDto);

    EventFullDto getEventUser(Long userId, Long eventId);

    EventFullDto updateEventUser(Long userId, Long eventId, UpdateEventUserDto request);

    EventFullDto getEventById(Long eventId);

    List<EventShortDto> getEventsByIds(List<Long> ids);

    List<EventShortDto> getRecommendations(Long userId, int maxResults);

    void likeEvent(Long userId, Long eventId);
}
