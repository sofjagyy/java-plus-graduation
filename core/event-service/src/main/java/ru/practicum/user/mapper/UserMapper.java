package ru.practicum.user.mapper;

import org.mapstruct.Mapper;
import ru.practicum.user.model.User;
import ru.practicum.user.dto.UserShortDto;

@Mapper(componentModel = "spring")
public interface UserMapper {
    UserShortDto toShortDto(User user);
}
