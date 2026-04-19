package ru.practicum.user.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import ru.practicum.user.dto.UserDto;

@FeignClient(name = "user-service")
public interface UserFeignClient {

    @GetMapping("/admin/users/{userId}")
    UserDto getUser(@PathVariable Long userId);
}
