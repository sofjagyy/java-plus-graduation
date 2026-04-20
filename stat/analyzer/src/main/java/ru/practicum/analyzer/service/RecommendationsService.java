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
import java.util.stream.Stream;

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

        Set<Long> candidateSet = new HashSet<>(candidates);
        List<EventSimilarity> candidateSimilarities = candidates.isEmpty()
                ? List.of()
                : similarityRepository.findByEventIds(candidates);

        Map<Long, List<EventSimilarity>> simsByCandidate = new HashMap<>();
        for (EventSimilarity sim : candidateSimilarities) {
            long a = sim.getEventA();
            long b = sim.getEventB();
            if (candidateSet.contains(a) && interactedEvents.contains(b)) {
                simsByCandidate.computeIfAbsent(a, id -> new ArrayList<>()).add(sim);
            }
            if (candidateSet.contains(b) && interactedEvents.contains(a)) {
                simsByCandidate.computeIfAbsent(b, id -> new ArrayList<>()).add(sim);
            }
        }

        Set<Long> neighborIds = simsByCandidate.values().stream()
                .flatMap(Collection::stream)
                .flatMap(s -> Stream.of(s.getEventA(), s.getEventB()))
                .filter(interactedEvents::contains)
                .collect(Collectors.toSet());

        Map<Long, Double> weightByNeighbor = neighborIds.isEmpty()
                ? Map.of()
                : userActionRepository.findByUserIdAndEventIdIn(userId, neighborIds).stream()
                .collect(Collectors.toMap(UserAction::getEventId, UserAction::getMaxWeight));

        for (long candidateId : candidates) {
            List<EventSimilarity> neighborSims = simsByCandidate.getOrDefault(candidateId, List.of()).stream()
                    .sorted(Comparator.comparingDouble(EventSimilarity::getScore).reversed())
                    .limit(k)
                    .toList();

            double weightedSum = 0;
            double simSum = 0;
            for (EventSimilarity ns : neighborSims) {
                long neighborId = ns.getEventA().equals(candidateId) ? ns.getEventB() : ns.getEventA();
                Double weight = weightByNeighbor.get(neighborId);
                if (weight == null) continue;
                weightedSum += ns.getScore() * weight;
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

        List<Long> eventIds = request.getEventIdList();
        if (eventIds.isEmpty()) {
            responseObserver.onCompleted();
            return;
        }

        Map<Long, Double> totals = userActionRepository.sumMaxWeightByEventIds(eventIds).stream()
                .collect(Collectors.toMap(
                        UserActionRepository.EventWeightSum::getEventId,
                        UserActionRepository.EventWeightSum::getTotal));

        for (long eventId : eventIds) {
            responseObserver.onNext(RecommendedEventProto.newBuilder()
                    .setEventId(eventId)
                    .setScore(totals.getOrDefault(eventId, 0.0))
                    .build());
        }

        responseObserver.onCompleted();
    }
}
