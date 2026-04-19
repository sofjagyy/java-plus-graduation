package ru.practicum.analyzer.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "user_action")
@IdClass(UserActionId.class)
@Getter
@Setter
public class UserAction {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Id
    @Column(name = "event_id")
    private Long eventId;

    @Column(name = "max_weight", nullable = false)
    private Double maxWeight;

    @Column(nullable = false)
    private Instant timestamp;
}
