package ru.practicum.analyzer.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.practicum.analyzer.model.UserAction;
import ru.practicum.analyzer.model.UserActionId;

import java.util.List;
import java.util.Optional;

public interface UserActionRepository extends JpaRepository<UserAction, UserActionId> {

    List<UserAction> findByUserIdOrderByTimestampDesc(Long userId);

    List<UserAction> findByUserId(Long userId);

    Optional<UserAction> findByUserIdAndEventId(Long userId, Long eventId);

    @Query("SELECT ua.eventId FROM UserAction ua WHERE ua.userId = :userId")
    List<Long> findEventIdsByUserId(@Param("userId") Long userId);

    @Query("SELECT COALESCE(SUM(ua.maxWeight), 0) FROM UserAction ua WHERE ua.eventId = :eventId")
    Double sumMaxWeightByEventId(@Param("eventId") Long eventId);
}
