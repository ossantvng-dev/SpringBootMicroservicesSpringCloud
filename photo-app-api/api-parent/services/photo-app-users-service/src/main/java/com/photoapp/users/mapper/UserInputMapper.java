package com.photoapp.users.mapper;

import com.photoapp.entity.User;
import com.photoapp.users.dto.CreateUserInputDTO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/*
    Lives here rather than in photo-app-commons because CreateUserInputDTO is owned by
    this service; commons cannot depend on it without a cycle.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface UserInputMapper {

    /*
        roles and passwordHash are set by the caller after mapping - roles are resolved
        against the role repository and the password is hashed, neither of which belongs
        in a mapper. activeUser keeps its @Builder.Default of true.
     */
    @Mapping(target = "roles", ignore = true)
    @Mapping(target = "passwordHash", ignore = true)
    @Mapping(target = "activeUser", ignore = true)
    User toEntity(CreateUserInputDTO createUserInputDTO);

}
