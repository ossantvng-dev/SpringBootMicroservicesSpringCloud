package com.photoapp.commons.mapper;

import com.photoapp.commons.dto.role.RoleDTO;
import com.photoapp.commons.dto.role.RoleNameDTO;
import com.photoapp.entity.Role;
import com.photoapp.entity.RoleName;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.Set;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface RoleMapper {

    RoleDTO toDTO(Role role);

    Set<RoleDTO> toDTOs(Set<Role> roles);

    /*
        Built through Lombok's builder, which only exposes the subclass fields. The
        BaseEntity fields (id, version, createdAt, updatedAt) are therefore unreachable
        here by construction - identity and auditing stay with the persistence layer.
     */
    Role toEntity(RoleDTO roleDTO);

    RoleNameDTO toDTO(RoleName roleName);

    RoleName toEntity(RoleNameDTO roleNameDTO);

}
