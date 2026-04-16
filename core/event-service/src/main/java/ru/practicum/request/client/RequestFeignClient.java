package ru.practicum.request.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.practicum.request.dto.ConfirmedCountDto;

import java.util.List;

@FeignClient(name = "request-service")
public interface RequestFeignClient {

    @GetMapping("/requests/confirmed")
    List<ConfirmedCountDto> getConfirmedCounts(@RequestParam List<Long> eventIds);
}
