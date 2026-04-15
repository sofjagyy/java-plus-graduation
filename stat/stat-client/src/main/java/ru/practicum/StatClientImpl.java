package ru.practicum;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.lang.NonNull;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ru.practicum.dto.EndpointHitDto;
import ru.practicum.dto.ViewStatsDto;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
@RequiredArgsConstructor
public class StatClientImpl implements StatClient {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final RestClient restClient;
    private final DiscoveryClient discoveryClient;
    private final RetryTemplate retryTemplate;

    @Value("${stats.service.id:stats-server}")
    private String statsServiceId;

    @Override
    public void hit(@NonNull EndpointHitDto paramHitDto) {
        restClient.post()
                .uri(makeUri("/hit"))
                .body(paramHitDto)
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public List<ViewStatsDto> getStat(LocalDateTime start, LocalDateTime end, List<String> uris, Boolean unique) {
        String query = "start=" + start.format(FORMATTER) + "&end=" + end.format(FORMATTER);

        if (uris != null && !uris.isEmpty()) {
            query += "&uris=" + String.join("&uris=", uris);
        }
        if (unique != null) {
            query += "&unique=" + unique;
        }

        URI uri = URI.create(makeUri("/stats") + "?" + query);

        return restClient.get()
                .uri(uri)
                .retrieve()
                .body(new ParameterizedTypeReference<List<ViewStatsDto>>() {});
    }

    private ServiceInstance getInstance() {
        try {
            return discoveryClient.getInstances(statsServiceId).getFirst();
        } catch (Exception exception) {
            throw new StatsServerUnavailable(
                    "Ошибка обнаружения адреса сервиса статистики с id: " + statsServiceId,
                    exception
            );
        }
    }

    private String makeUri(String path) {
        ServiceInstance instance = retryTemplate.execute(ctx -> getInstance());
        return "http://" + instance.getHost() + ":" + instance.getPort() + path;
    }
}
