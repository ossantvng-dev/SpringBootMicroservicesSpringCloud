package com.photoapp.commons.mapper;

import com.photoapp.commons.dto.user.UserDTO;
import com.photoapp.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(
        componentModel = "spring",
        uses = RoleMapper.class,
        unmappedTargetPolicy = ReportingPolicy.ERROR
)
public interface UserMapper {

    /*
        passwordHash is deliberately absent from UserDTO and never leaves the service.
     */
    UserDTO toDTO(User user);

}
