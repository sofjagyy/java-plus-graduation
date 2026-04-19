package ru.practicum.request.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import ru.practicum.event.client.EventFeignClient;
import ru.practicum.event.dto.EventRequestStatusUpdateDto;
import ru.practicum.event.dto.EventRequestStatusUpdateResult;
import ru.practicum.request.dto.ConfirmedCountDto;
import ru.practicum.request.dto.ParticipationRequestDto;
import ru.practicum.request.service.ParticipationRequestService;

import java.util.List;

@Slf4j
@RestController
@AllArgsConstructor
@Validated
public class ParticipationRequestController {

    private final ParticipationRequestService requestService;
    private final EventFeignClient eventFeignClient;

    @PostMapping("/users/{userId}/requests")
    @ResponseStatus(HttpStatus.CREATED)
    public ParticipationRequestDto create(@PathVariable @Positive Long userId,
                                          @RequestParam @Positive Long eventId) {
        log.info("POST: Создание запроса. userId: {}, eventId: {}", userId, eventId);
        return requestService.create(userId, eventId, eventFeignClient.getEventByIdInternal(eventId));
    }

    @GetMapping("/users/{userId}/requests")
    public List<ParticipationRequestDto> getRequests(@PathVariable @Positive Long userId) {
        log.info("GET: Заявки пользователя userId={}", userId);
        return requestService.getRequests(userId);
    }

    @PatchMapping("/users/{userId}/requests/{requestId}/cancel")
    public ParticipationRequestDto cancelRequest(@PathVariable @Positive Long userId,
                                                 @PathVariable @Positive Long requestId) {
        log.info("PATCH: Отмена участия userId={}, requestId={}", userId, requestId);
        return requestService.cancelRequest(userId, requestId);
    }

    @GetMapping("/users/{userId}/events/{eventId}/requests")
    public List<ParticipationRequestDto> getEventRequests(@PathVariable @Positive Long userId,
                                                          @PathVariable @Positive Long eventId) {
        log.info("GET: Заявки на событие eventId={} от инициатора userId={}", eventId, userId);
        return requestService.getEventRequests(userId, eventId);
    }

    @PatchMapping("/users/{userId}/events/{eventId}/requests")
    public EventRequestStatusUpdateResult changeRequestStatus(@PathVariable @Positive Long userId,
                                                              @PathVariable @Positive Long eventId,
                                                              @Valid @RequestBody EventRequestStatusUpdateDto request) {
        log.info("PATCH: Изменение статусов заявок eventId={}, userId={}", eventId, userId);
        return requestService.changeRequestStatus(userId, eventId, request);
    }

    @GetMapping("/requests/confirmed")
    public List<ConfirmedCountDto> getConfirmedCounts(@RequestParam List<Long> eventIds) {
        return requestService.getConfirmedCounts(eventIds);
    }
}
