package ru.practicum.analyzer.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.practicum.analyzer.model.EventSimilarity;
import ru.practicum.analyzer.model.EventSimilarityId;

import java.util.List;

public interface EventSimilarityRepository extends JpaRepository<EventSimilarity, EventSimilarityId> {

    @Query("SELECT es FROM EventSimilarity es WHERE es.eventA = :eventId OR es.eventB = :eventId ORDER BY es.score DESC")
    List<EventSimilarity> findByEventId(@Param("eventId") Long eventId);

    @Query("SELECT es FROM EventSimilarity es WHERE (es.eventA IN :eventIds OR es.eventB IN :eventIds) ORDER BY es.score DESC")
    List<EventSimilarity> findByEventIds(@Param("eventIds") List<Long> eventIds);
}
