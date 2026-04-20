package ru.practicum.aggregator.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import ru.practicum.ewm.stats.avro.ActionTypeAvro;
import ru.practicum.ewm.stats.avro.EventSimilarityAvro;
import ru.practicum.ewm.stats.avro.UserActionAvro;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class SimilarityService {

    private static final String SIMILARITY_TOPIC = "stats.events-similarity.v1";

    private final KafkaTemplate<String, EventSimilarityAvro> kafkaTemplate;

    private final Map<Long, Map<Long, Double>> eventUserWeights = new HashMap<>();
    private final Map<Long, Double> eventWeightSums = new HashMap<>();
    private final Map<Long, Map<Long, Double>> minWeightSums = new HashMap<>();

    @KafkaListener(topics = "stats.user-actions.v1", groupId = "aggregator-group")
    public void consume(UserActionAvro action) {
        long userId = action.getUserId();
        long eventId = action.getEventId();
        double newWeight = getWeight(action.getActionType());
        Instant timestamp = action.getTimestamp();
        log.info("Consumed action: userId={}, eventId={}, type={}, weight={}", userId, eventId, action.getActionType(), newWeight);

        Map<Long, Double> userWeights = eventUserWeights.computeIfAbsent(eventId, k -> new HashMap<>());
        double oldWeight = userWeights.getOrDefault(userId, 0.0);

        if (newWeight <= oldWeight) {
            return;
        }

        userWeights.put(userId, newWeight);

        double oldSumA = eventWeightSums.getOrDefault(eventId, 0.0);
        double newSumA = oldSumA - oldWeight + newWeight;
        eventWeightSums.put(eventId, newSumA);

        Set<Long> allEvents = eventUserWeights.keySet();
        for (long otherEventId : allEvents) {
            if (otherEventId == eventId) continue;

            Map<Long, Double> otherUserWeights = eventUserWeights.get(otherEventId);
            if (!otherUserWeights.containsKey(userId)) continue;

            double otherWeight = otherUserWeights.get(userId);

            double oldMin = Math.min(oldWeight, otherWeight);
            double newMin = Math.min(newWeight, otherWeight);
            double delta = newMin - oldMin;

            long first = Math.min(eventId, otherEventId);
            long second = Math.max(eventId, otherEventId);

            double currentSmin = getMinWeightSum(first, second);
            if (delta != 0) {
                putMinWeightSum(first, second, currentSmin + delta);
            }

            double updatedSmin = getMinWeightSum(first, second);
            if (updatedSmin <= 0) continue;

            double sA = eventWeightSums.getOrDefault(eventId, 0.0);
            double sB = eventWeightSums.getOrDefault(otherEventId, 0.0);

            double denominator = Math.sqrt(sA) * Math.sqrt(sB);
            double similarity = denominator > 0 ? updatedSmin / denominator : 0.0;

            EventSimilarityAvro simAvro = EventSimilarityAvro.newBuilder()
                    .setEventA(first)
                    .setEventB(second)
                    .setScore(similarity)
                    .setTimestamp(timestamp)
                    .build();

            kafkaTemplate.send(SIMILARITY_TOPIC, first + ":" + second, simAvro);
            log.info("Similarity({}, {}) = {}", first, second, similarity);
        }
    }

    private double getWeight(ActionTypeAvro actionType) {
        return switch (actionType) {
            case VIEW -> 0.4;
            case REGISTER -> 0.8;
            case LIKE -> 1.0;
        };
    }

    private double getMinWeightSum(long eventA, long eventB) {
        long first = Math.min(eventA, eventB);
        long second = Math.max(eventA, eventB);
        return minWeightSums
                .computeIfAbsent(first, k -> new HashMap<>())
                .getOrDefault(second, 0.0);
    }

    private void putMinWeightSum(long eventA, long eventB, double sum) {
        long first = Math.min(eventA, eventB);
        long second = Math.max(eventA, eventB);
        minWeightSums
                .computeIfAbsent(first, k -> new HashMap<>())
                .put(second, sum);
    }
}
