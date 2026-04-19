package ru.practicum.analyzer.service;

import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import ru.practicum.analyzer.model.EventSimilarity;
import ru.practicum.analyzer.model.UserAction;
import ru.practicum.analyzer.repository.EventSimilarityRepository;
import ru.practicum.analyzer.repository.UserActionRepository;
import stats.service.dashboard.*;

import java.util.*;
import java.util.stream.Collectors;

@GrpcService
@Slf4j
@RequiredArgsConstructor
public class RecommendationsService extends RecommendationsControllerGrpc.RecommendationsControllerImplBase {

    private final EventSimilarityRepository similarityRepository;
    private final UserActionRepository userActionRepository;

    @Override
    public void getRecommendationsForUser(UserPredictionsRequestProto request,
                                          StreamObserver<RecommendedEventProto> responseObserver) {
        long userId = request.getUserId();
        int maxResults = request.getMaxResults();

        log.info("GetRecommendationsForUser: userId={}, maxResults={}", userId, maxResults);

        List<UserAction> userActions = userActionRepository.findByUserIdOrderByTimestampDesc(userId);
        if (userActions.isEmpty()) {
            responseObserver.onCompleted();
            return;
        }

        Set<Long> interactedEvents = userActions.stream()
                .map(UserAction::getEventId)
                .collect(Collectors.toSet());

        int recentLimit = Math.min(userActions.size(), maxResults);
        List<Long> recentEventIds = userActions.subList(0, recentLimit).stream()
                .map(UserAction::getEventId)
                .toList();

        List<EventSimilarity> similarities = similarityRepository.findByEventIds(recentEventIds);

        Map<Long, Double> candidateScores = new HashMap<>();
        for (EventSimilarity sim : similarities) {
            long other = interactedEvents.contains(sim.getEventA()) && !interactedEvents.contains(sim.getEventB())
                    ? sim.getEventB()
                    : (!interactedEvents.contains(sim.getEventA()) && interactedEvents.contains(sim.getEventB())
                    ? sim.getEventA() : -1);
            if (other == -1) continue;
            candidateScores.merge(other, sim.getScore(), Math::max);
        }

        List<Long> candidates = candidateScores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(maxResults)
                .map(Map.Entry::getKey)
                .toList();

        int k = 5;
        for (long candidateId : candidates) {
            List<EventSimilarity> neighborSims = similarityRepository.findByEventId(candidateId).stream()
                    .filter(s -> {
                        long otherId = s.getEventA().equals(candidateId) ? s.getEventB() : s.getEventA();
                        return interactedEvents.contains(otherId);
                    })
                    .limit(k)
                    .toList();

            double weightedSum = 0;
            double simSum = 0;
            for (EventSimilarity ns : neighborSims) {
                long neighborId = ns.getEventA().equals(candidateId) ? ns.getEventB() : ns.getEventA();
                UserAction ua = userActionRepository.findByUserIdAndEventId(userId, neighborId).orElse(null);
                if (ua == null) continue;
                weightedSum += ns.getScore() * ua.getMaxWeight();
                simSum += ns.getScore();
            }

            double predictedScore = simSum > 0 ? weightedSum / simSum : 0;

            responseObserver.onNext(RecommendedEventProto.newBuilder()
                    .setEventId(candidateId)
                    .setScore(predictedScore)
                    .build());
        }

        responseObserver.onCompleted();
    }

    @Override
    public void getSimilarEvents(SimilarEventsRequestProto request,
                                 StreamObserver<RecommendedEventProto> responseObserver) {
        long eventId = request.getEventId();
        long userId = request.getUserId();
        int maxResults = request.getMaxResults();

        log.info("GetSimilarEvents: eventId={}, userId={}, maxResults={}", eventId, userId, maxResults);

        Set<Long> interactedEvents = new HashSet<>(userActionRepository.findEventIdsByUserId(userId));

        List<EventSimilarity> similarities = similarityRepository.findByEventId(eventId);

        similarities.stream()
                .map(sim -> {
                    long otherId = sim.getEventA().equals(eventId) ? sim.getEventB() : sim.getEventA();
                    return Map.entry(otherId, sim.getScore());
                })
                .filter(e -> !interactedEvents.contains(e.getKey()))
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(maxResults)
                .forEach(e -> responseObserver.onNext(
                        RecommendedEventProto.newBuilder()
                                .setEventId(e.getKey())
                                .setScore(e.getValue())
                                .build()));

        responseObserver.onCompleted();
    }

    @Override
    public void getInteractionsCount(InteractionsCountRequestProto request,
                                     StreamObserver<RecommendedEventProto> responseObserver) {
        log.info("GetInteractionsCount for {} events", request.getEventIdCount());

        for (long eventId : request.getEventIdList()) {
            double totalWeight = userActionRepository.sumMaxWeightByEventId(eventId);
            responseObserver.onNext(RecommendedEventProto.newBuilder()
                    .setEventId(eventId)
                    .setScore(totalWeight)
                    .build());
        }

        responseObserver.onCompleted();
    }
}
