package ru.practicum.event.service;

import com.querydsl.core.BooleanBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.AnalyzerClient;
import ru.practicum.CollectorClient;
import ru.practicum.category.model.Category;
import ru.practicum.category.repository.CategoryRepository;
import ru.practicum.event.dto.*;
import ru.practicum.event.mapper.EventMapper;
import ru.practicum.event.model.Event;
import ru.practicum.event.model.EventState;
import ru.practicum.event.model.Location;
import ru.practicum.event.model.QEvent;
import ru.practicum.event.repository.EventRepository;
import ru.practicum.exception.ConflictException;
import ru.practicum.exception.NotFoundException;
import ru.practicum.exception.ValidationException;
import ru.practicum.request.client.RequestFeignClient;
import ru.practicum.request.dto.ConfirmedCountDto;
import ru.practicum.user.client.UserFeignClient;
import ru.practicum.user.dto.UserDto;
import ru.practicum.user.model.User;
import ru.practicum.user.repository.UserRepository;
import stats.service.collector.ActionTypeProto;
import stats.service.dashboard.RecommendedEventProto;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventServiceImpl implements EventService {

    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final CollectorClient collectorClient;
    private final AnalyzerClient analyzerClient;
    private final EventMapper eventMapper;
    private final UserFeignClient userFeignClient;
    private final RequestFeignClient requestFeignClient;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public List<EventFullDto> getEventsAdmin(EventAdminFilterParams params) {
        BooleanBuilder builder = new BooleanBuilder();
        QEvent qEvent = QEvent.event;

        if (params.getUsers() != null && !params.getUsers().isEmpty()) {
            builder.and(qEvent.initiator.id.in(params.getUsers()));
        }
        if (params.getStates() != null && !params.getStates().isEmpty()) {
            builder.and(qEvent.state.in(params.getStates().stream()
                    .map(EventState::valueOf)
                    .collect(Collectors.toList())));
        }
        if (params.getCategories() != null && !params.getCategories().isEmpty()) {
            builder.and(qEvent.category.id.in(params.getCategories()));
        }
        if (params.getRangeStart() != null) {
            builder.and(qEvent.eventDate.goe(parseTime(params.getRangeStart())));
        }
        if (params.getRangeEnd() != null) {
            builder.and(qEvent.eventDate.loe(parseTime(params.getRangeEnd())));
        }

        PageRequest pageRequest = PageRequest.of(params.getFrom() / params.getSize(), params.getSize());
        List<Event> events = eventRepository.findAll(builder, pageRequest).getContent();

        return makeEventFullDtoList(events);
    }

    @Override
    @Transactional
    public EventFullDto updateEventAdmin(Long eventId, UpdateEventAdminDto request) {
        Event event = getEventByIdOrThrow(eventId);

        if (request.getEventDate() != null) {
            if (request.getEventDate().isBefore(LocalDateTime.now().plusHours(1))) {
                throw new ValidationException("Event date must be at least 1 hour from now");
            }
        }

        if (request.getStateAction() != null) {
            if (request.getStateAction() == UpdateEventAdminDto.StateAction.PUBLISH_EVENT) {
                if (event.getState() != EventState.PENDING) {
                    throw new ConflictException("Cannot publish the event because it's not in the right state: " + event.getState());
                }
                event.setState(EventState.PUBLISHED);
                event.setPublishedOn(LocalDateTime.now());
            } else if (request.getStateAction() == UpdateEventAdminDto.StateAction.REJECT_EVENT) {
                if (event.getState() == EventState.PUBLISHED) {
                    throw new ConflictException("Cannot reject the event because it's already published");
                }
                event.setState(EventState.CANCELED);
            }
        }

        return toEventFullDtoWithStats(updateEvent(event, request));
    }

    @Override
    public List<EventShortDto> getEventsPublic(EventPublicFilterParams params) {
        BooleanBuilder builder = new BooleanBuilder();
        QEvent qEvent = QEvent.event;

        builder.and(qEvent.state.eq(EventState.PUBLISHED));

        if (params.getText() != null && !params.getText().isBlank()) {
            builder.and(qEvent.annotation.containsIgnoreCase(params.getText())
                    .or(qEvent.description.containsIgnoreCase(params.getText())));
        }
        if (params.getCategories() != null && !params.getCategories().isEmpty()) {
            builder.and(qEvent.category.id.in(params.getCategories()));
        }
        if (params.getPaid() != null) {
            builder.and(qEvent.paid.eq(params.getPaid()));
        }

        LocalDateTime start = (params.getRangeStart() != null) ? parseTime(params.getRangeStart()) : LocalDateTime.now();
        LocalDateTime end = (params.getRangeEnd() != null) ? parseTime(params.getRangeEnd()) : null;

        if (end != null && start.isAfter(end)) {
            throw new ValidationException("Start date must be before end date");
        }

        builder.and(qEvent.eventDate.goe(start));
        if (end != null) {
            builder.and(qEvent.eventDate.loe(end));
        }

        Sort sortOrder = Sort.by(Sort.Direction.ASC, "eventDate");

        PageRequest pageRequest = PageRequest.of(params.getFrom() / params.getSize(), params.getSize(), sortOrder);
        List<Event> events = eventRepository.findAll(builder, pageRequest).getContent();

        return makeEventShortDtoList(events);
    }

    @Override
    public EventFullDto getEventPublic(Long id, Long userId) {
        Event event = getEventByIdOrThrow(id);

        if (event.getState() != EventState.PUBLISHED) {
            throw new NotFoundException("Event must be published");
        }

        if (userId != null) {
            try {
                collectorClient.sendUserAction(userId, id, ActionTypeProto.ACTION_VIEW);
            } catch (Exception e) {
                log.error("Error sending view action to collector", e);
            }
        }

        return toEventFullDtoWithStats(event);
    }

    @Override
    public List<EventShortDto> getEventsUser(Long userId, int from, int size) {
        checkUser(userId);
        PageRequest pageRequest = PageRequest.of(from / size, size);
        List<Event> events = eventRepository.findAllByInitiatorId(userId, pageRequest);
        return makeEventShortDtoList(events);
    }

    @Override
    @Transactional
    public EventFullDto createEventUser(Long userId, NewEventDto newEventDto) {
        User user = checkUser(userId);

        if (newEventDto.getEventDate().isBefore(LocalDateTime.now().plusHours(2))) {
            throw new ValidationException("Event date must be at least 2 hours from now");
        }

        Category category = categoryRepository.findById(newEventDto.getCategory())
                .orElseThrow(() -> new NotFoundException("Category not found"));

        Event event = eventMapper.toEntity(newEventDto);
        event.setInitiator(user);
        event.setCategory(category);
        event.setState(EventState.PENDING);
        event.setCreatedOn(LocalDateTime.now());

        Location location = eventMapper.toLocation(newEventDto.getLocation());
        event.setLocation(location);

        return toEventFullDtoWithStats(eventRepository.save(event));
    }

    @Override
    public EventFullDto getEventUser(Long userId, Long eventId) {
        checkUser(userId);
        Event event = eventRepository.findByIdAndInitiatorId(eventId, userId)
                .orElseThrow(() -> new NotFoundException("Event not found"));
        return toEventFullDtoWithStats(event);
    }

    @Override
    @Transactional
    public EventFullDto updateEventUser(Long userId, Long eventId, UpdateEventUserDto request) {
        checkUser(userId);
        Event event = eventRepository.findByIdAndInitiatorId(eventId, userId)
                .orElseThrow(() -> new NotFoundException("Event not found"));

        if (event.getState() == EventState.PUBLISHED) {
            throw new ConflictException("Only pending or canceled events can be changed");
        }

        if (request.getEventDate() != null) {
            if (request.getEventDate().isBefore(LocalDateTime.now().plusHours(2))) {
                throw new ValidationException("Event date must be at least 2 hours from now");
            }
        }

        if (request.getStateAction() != null) {
            if (request.getStateAction() == UpdateEventUserDto.StateAction.SEND_TO_REVIEW) {
                event.setState(EventState.PENDING);
            } else if (request.getStateAction() == UpdateEventUserDto.StateAction.CANCEL_REVIEW) {
                event.setState(EventState.CANCELED);
            }
        }

        return toEventFullDtoWithStats(updateEvent(event, request));
    }

    @Override
    public EventFullDto getEventById(Long eventId) {
        return toEventFullDtoWithStats(getEventByIdOrThrow(eventId));
    }

    @Override
    public List<EventShortDto> getEventsByIds(List<Long> ids) {
        return makeEventShortDtoList(eventRepository.findAllById(ids));
    }

    private Event updateEvent(Event event, BaseUpdateEventDto request) {
        if (request.getTitle() != null) event.setTitle(request.getTitle());
        if (request.getAnnotation() != null) event.setAnnotation(request.getAnnotation());
        if (request.getDescription() != null) event.setDescription(request.getDescription());
        if (request.getEventDate() != null) event.setEventDate(request.getEventDate());
        if (request.getCategory() != null) {
            Category category = categoryRepository.findById(request.getCategory())
                    .orElseThrow(() -> new NotFoundException("Category not found"));
            event.setCategory(category);
        }
        if (request.getLocation() != null) {
            event.setLocation(eventMapper.toLocation(request.getLocation()));
        }
        if (request.getPaid() != null) event.setPaid(request.getPaid());
        if (request.getParticipantLimit() != null) event.setParticipantLimit(request.getParticipantLimit());
        if (request.getRequestModeration() != null) event.setRequestModeration(request.getRequestModeration());

        return eventRepository.save(event);
    }

    private User checkUser(Long userId) {
        UserDto userDto = userFeignClient.getUser(userId);
        return userRepository.findById(userId).orElseGet(() -> {
            User user = new User();
            user.setId(userDto.getId());
            user.setName(userDto.getName());
            user.setEmail(userDto.getEmail());
            return userRepository.save(user);
        });
    }

    private LocalDateTime parseTime(String time) {
        return LocalDateTime.parse(time, FORMATTER);
    }

    @Override
    public List<EventShortDto> getRecommendations(Long userId, int maxResults) {
        try {
            List<Long> recommendedIds = analyzerClient.getRecommendationsForUser(userId, maxResults)
                    .map(RecommendedEventProto::getEventId)
                    .collect(Collectors.toList());
            if (recommendedIds.isEmpty()) return Collections.emptyList();
            List<Event> events = eventRepository.findAllById(recommendedIds);
            return makeEventShortDtoList(events);
        } catch (Exception e) {
            log.error("Error getting recommendations", e);
            return Collections.emptyList();
        }
    }

    @Override
    @Transactional
    public void likeEvent(Long userId, Long eventId) {
        checkUser(userId);
        collectorClient.sendUserAction(userId, eventId, ActionTypeProto.ACTION_LIKE);
    }

    private List<EventFullDto> makeEventFullDtoList(List<Event> events) {
        Map<Long, Double> ratings = getRatings(events);
        Map<Long, Long> confirmedRequests = getConfirmedRequests(events);

        return events.stream()
                .map(event -> {
                    EventFullDto dto = eventMapper.toFullDto(event);
                    dto.setRating(ratings.getOrDefault(event.getId(), 0.0));
                    dto.setConfirmedRequests(confirmedRequests.getOrDefault(event.getId(), 0L));
                    return dto;
                })
                .collect(Collectors.toList());
    }

    private List<EventShortDto> makeEventShortDtoList(List<Event> events) {
        Map<Long, Double> ratings = getRatings(events);
        Map<Long, Long> confirmedRequests = getConfirmedRequests(events);

        return events.stream()
                .map(event -> {
                    EventShortDto dto = eventMapper.toShortDto(event);
                    dto.setRating(ratings.getOrDefault(event.getId(), 0.0));
                    dto.setConfirmedRequests(confirmedRequests.getOrDefault(event.getId(), 0L));
                    return dto;
                })
                .collect(Collectors.toList());
    }

    private EventFullDto toEventFullDtoWithStats(Event event) {
        return makeEventFullDtoList(List.of(event)).get(0);
    }

    private Map<Long, Double> getRatings(List<Event> events) {
        if (events.isEmpty()) return Collections.emptyMap();

        List<Long> eventIds = events.stream().map(Event::getId).collect(Collectors.toList());
        Map<Long, Double> ratings = new HashMap<>();
        try {
            analyzerClient.getInteractionsCount(eventIds)
                    .forEach(r -> ratings.put(r.getEventId(), r.getScore()));
        } catch (Exception e) {
            log.error("Error getting ratings from analyzer", e);
        }
        return ratings;
    }

    private Map<Long, Long> getConfirmedRequests(List<Event> events) {
        if (events.isEmpty()) return Collections.emptyMap();

        List<Long> eventIds = events.stream().map(Event::getId).collect(Collectors.toList());

        try {
            List<ConfirmedCountDto> counts = requestFeignClient.getConfirmedCounts(eventIds);
            return counts.stream()
                    .collect(Collectors.toMap(ConfirmedCountDto::getEventId, ConfirmedCountDto::getCount));
        } catch (Exception e) {
            log.error("Error getting confirmed counts from request-service", e);
            return Collections.emptyMap();
        }
    }

    private Event getEventByIdOrThrow(Long eventId) {
        return eventRepository.findById(eventId)
                .orElseThrow(() -> new NotFoundException("Event with id=" + eventId + " was not found"));
    }
}
