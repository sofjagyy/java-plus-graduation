package ru.practicum.request.service;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.event.client.EventFeignClient;
import ru.practicum.event.dto.EventFullDto;
import ru.practicum.event.dto.EventRequestStatusUpdateDto;
import ru.practicum.event.dto.EventRequestStatusUpdateResult;
import ru.practicum.event.model.EventState;
import ru.practicum.event.repository.EventRepository;
import ru.practicum.exception.ConflictException;
import ru.practicum.exception.DuplicatedException;
import ru.practicum.exception.NotFoundException;
import ru.practicum.request.dto.ConfirmedCountDto;
import ru.practicum.request.dto.ParticipationRequestDto;
import ru.practicum.request.enums.RequestStatus;
import ru.practicum.request.mapper.ParticipationRequestMapper;
import ru.practicum.request.model.ParticipationRequest;
import ru.practicum.request.repository.ConfirmedRequestView;
import ru.practicum.request.repository.ParticipationRequestRepository;
import ru.practicum.CollectorClient;
import ru.practicum.user.client.UserFeignClient;
import ru.practicum.user.dto.UserDto;
import ru.practicum.user.repository.UserRepository;
import stats.service.collector.ActionTypeProto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@AllArgsConstructor
@Transactional(readOnly = true)
public class ParticipationRequestServiceImpl implements ParticipationRequestService {

    private final ParticipationRequestRepository repository;
    private final UserFeignClient userFeignClient;
    private final EventFeignClient eventFeignClient;
    private final ParticipationRequestMapper mapper;
    private final UserRepository userRepository;
    private final EventRepository eventRepository;
    private final CollectorClient collectorClient;

    @Override
    @Transactional
    public ParticipationRequestDto create(Long userId, Long eventId, EventFullDto event) {
        if (repository.findByEventIdAndRequesterId(eventId, userId).isPresent()) {
            throw new DuplicatedException("Такая заявка уже создана");
        }

        if (Objects.equals(event.getInitiator().getId(), userId)) {
            throw new ConflictException("Инициатор события не может добавить запрос на участие в своём событии");
        }

        if (event.getState() != EventState.PUBLISHED) {
            throw new ConflictException("Нельзя участвовать в неопубликованном событии");
        }

        int confirmedRequestsCount = repository.findAllByEventIdAndStatus(eventId, RequestStatus.CONFIRMED).size();

        if (event.getParticipantLimit() > 0 && confirmedRequestsCount >= event.getParticipantLimit()) {
            throw new ConflictException("Достигнут лимит запросов на участие");
        }

        UserDto userDto = userFeignClient.getUser(userId);
        syncUser(userDto);
        syncEvent(event.getId());

        var user = userRepository.getReferenceById(userId);
        var eventEntity = eventRepository.getReferenceById(event.getId());

        RequestStatus status = RequestStatus.PENDING;
        if (!Boolean.TRUE.equals(event.getRequestModeration()) || Objects.equals(event.getParticipantLimit(), 0)) {
            status = RequestStatus.CONFIRMED;
        }

        ParticipationRequest request = ParticipationRequest.builder()
                .requester(user)
                .event(eventEntity)
                .status(status)
                .created(LocalDateTime.now())
                .build();

        ParticipationRequest participationRequest = repository.save(request);
        repository.flush();
        log.info("Запрос успешно создан. Параметры: {}", participationRequest);

        try {
            collectorClient.sendUserAction(userId, eventId, ActionTypeProto.ACTION_REGISTER);
        } catch (Exception e) {
            log.error("Failed to send REGISTER action to collector", e);
        }

        return mapper.toDto(participationRequest);
    }

    @Override
    public List<ParticipationRequestDto> getRequests(Long userId) {
        userFeignClient.getUser(userId);
        return repository.findAllByRequesterId(userId).stream().map(mapper::toDto).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public ParticipationRequestDto cancelRequest(Long userId, Long requestId) {
        ParticipationRequest request = repository.findById(requestId)
                .orElseThrow(() -> new NotFoundException("Заявка не найдена"));

        UserDto user = userFeignClient.getUser(request.getRequester().getId());
        if (!user.getId().equals(userId)) {
            log.error("Попытка отменить чужую заявку: userId={}, заявка принадлежит userId={}", userId, user.getId());
            throw new ConflictException("Пользователь, который не является автором заявки, не может её отменить.");
        }

        request.setStatus(RequestStatus.CANCELED);
        log.info("Статус заявки с id={} изменен на CANCELED", requestId);

        ParticipationRequestDto requestDto = mapper.toDto(repository.save(request));
        repository.flush();
        log.info("Участие в событии для пользователя с id={} отменено", userId);
        return requestDto;
    }

    @Override
    public List<ParticipationRequestDto> getEventRequests(Long userId, Long eventId) {
        userFeignClient.getUser(userId);
        EventFullDto event = eventFeignClient.getEventByIdInternal(eventId);
        if (!event.getInitiator().getId().equals(userId)) {
            throw new ConflictException("Пользователь не является инициатором события");
        }
        return repository.findAllByEventId(eventId).stream()
                .map(mapper::toDto)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public EventRequestStatusUpdateResult changeRequestStatus(Long userId, Long eventId, EventRequestStatusUpdateDto request) {
        userFeignClient.getUser(userId);
        EventFullDto event = eventFeignClient.getEventByIdInternal(eventId);
        if (!event.getInitiator().getId().equals(userId)) {
            throw new ConflictException("Пользователь не является инициатором события");
        }

        if (!event.getRequestModeration() || event.getParticipantLimit() == 0) {
            throw new ConflictException("Moderation is not required");
        }

        Long confirmedCount = repository.countByEventIdAndStatus(eventId, RequestStatus.CONFIRMED);
        if (event.getParticipantLimit() <= confirmedCount) {
            throw new ConflictException("The participant limit has been reached");
        }

        List<ParticipationRequest> requests = repository.findAllByIdIn(request.getRequestIds());
        List<ParticipationRequestDto> confirmed = new ArrayList<>();
        List<ParticipationRequestDto> rejected = new ArrayList<>();

        for (ParticipationRequest req : requests) {
            if (req.getStatus() != RequestStatus.PENDING) {
                throw new ConflictException("Request must have status PENDING");
            }

            if (request.getStatus() == RequestStatus.CONFIRMED) {
                if (confirmedCount < event.getParticipantLimit()) {
                    req.setStatus(RequestStatus.CONFIRMED);
                    confirmed.add(mapper.toDto(req));
                    confirmedCount++;
                } else {
                    req.setStatus(RequestStatus.REJECTED);
                    rejected.add(mapper.toDto(req));
                }
            } else {
                req.setStatus(RequestStatus.REJECTED);
                rejected.add(mapper.toDto(req));
            }
        }
        repository.saveAll(requests);
        return new EventRequestStatusUpdateResult(confirmed, rejected);
    }

    @Override
    public List<ConfirmedCountDto> getConfirmedCounts(List<Long> eventIds) {
        List<ConfirmedRequestView> views = repository.countByEventIdInAndStatus(eventIds, RequestStatus.CONFIRMED);
        return views.stream()
                .map(v -> new ConfirmedCountDto(v.getEventId(), v.getCount()))
                .collect(Collectors.toList());
    }

    private void syncUser(UserDto userDto) {
        if (!userRepository.existsById(userDto.getId())) {
            ru.practicum.user.model.User user = new ru.practicum.user.model.User();
            user.setId(userDto.getId());
            user.setName(userDto.getName());
            user.setEmail(userDto.getEmail());
            userRepository.saveAndFlush(user);
        }
    }

    private void syncEvent(Long eventId) {
        if (!eventRepository.existsById(eventId)) {
            ru.practicum.event.model.Event event = new ru.practicum.event.model.Event();
            event.setId(eventId);
            eventRepository.saveAndFlush(event);
        }
    }
}
