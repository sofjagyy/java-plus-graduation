package ru.practicum.analyzer.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.practicum.analyzer.model.UserAction;
import ru.practicum.analyzer.repository.UserActionRepository;
import ru.practicum.ewm.stats.avro.ActionTypeAvro;
import ru.practicum.ewm.stats.avro.UserActionAvro;

import java.time.Instant;

@Component
@Slf4j
@RequiredArgsConstructor
public class UserActionConsumer {

    private final UserActionRepository userActionRepository;

    @KafkaListener(topics = "stats.user-actions.v1", containerFactory = "userActionListenerFactory")
    public void consume(UserActionAvro action) {
        log.debug("Analyzer received action: userId={}, eventId={}", action.getUserId(), action.getEventId());

        double weight = getWeight(action.getActionType());
        Instant ts = action.getTimestamp();

        userActionRepository.findByUserIdAndEventId(action.getUserId(), action.getEventId())
                .ifPresentOrElse(
                        existing -> {
                            if (weight > existing.getMaxWeight()) {
                                existing.setMaxWeight(weight);
                                existing.setTimestamp(ts);
                                userActionRepository.save(existing);
                            }
                        },
                        () -> {
                            UserAction ua = new UserAction();
                            ua.setUserId(action.getUserId());
                            ua.setEventId(action.getEventId());
                            ua.setMaxWeight(weight);
                            ua.setTimestamp(ts);
                            userActionRepository.save(ua);
                        }
                );
    }

    private double getWeight(ActionTypeAvro actionType) {
        return switch (actionType) {
            case VIEW -> 0.4;
            case REGISTER -> 0.8;
            case LIKE -> 1.0;
        };
    }
}
