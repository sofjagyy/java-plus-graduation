package ru.practicum.analyzer.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.practicum.analyzer.model.EventSimilarity;
import ru.practicum.analyzer.model.EventSimilarityId;
import ru.practicum.analyzer.repository.EventSimilarityRepository;
import ru.practicum.ewm.stats.avro.EventSimilarityAvro;

@Component
@Slf4j
@RequiredArgsConstructor
public class EventSimilarityConsumer {

    private final EventSimilarityRepository eventSimilarityRepository;

    @KafkaListener(topics = "stats.events-similarity.v1", containerFactory = "similarityListenerFactory")
    public void consume(EventSimilarityAvro sim) {
        log.debug("Analyzer received similarity: eventA={}, eventB={}, score={}", sim.getEventA(), sim.getEventB(), sim.getScore());

        EventSimilarityId id = new EventSimilarityId(sim.getEventA(), sim.getEventB());

        eventSimilarityRepository.findById(id)
                .ifPresentOrElse(
                        existing -> {
                            existing.setScore(sim.getScore());
                            existing.setTimestamp(sim.getTimestamp());
                            eventSimilarityRepository.save(existing);
                        },
                        () -> {
                            EventSimilarity es = new EventSimilarity();
                            es.setEventA(sim.getEventA());
                            es.setEventB(sim.getEventB());
                            es.setScore(sim.getScore());
                            es.setTimestamp(sim.getTimestamp());
                            eventSimilarityRepository.save(es);
                        }
                );
    }
}
