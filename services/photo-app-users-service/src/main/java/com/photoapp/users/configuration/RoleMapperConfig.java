package com.photoapp.users.configuration;

import com.photoapp.commons.dto.role.RoleDTO;
import com.photoapp.entity.Role;
import org.modelmapper.ModelMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RoleMapperConfig {

    @Bean
    public ModelMapper roleModelMapper(ModelMapper modelMapper) {
        // Role -> RoleDTO
        modelMapper.typeMap(Role.class, RoleDTO.class)
                .addMappings(mapper -> mapper.map(Role::getName, RoleDTO::setName));

        // RoleDTO -> Role
        modelMapper.typeMap(RoleDTO.class, Role.class)
                .addMappings(mapper -> mapper.map(RoleDTO::getName, Role::setName));

        return modelMapper;
    }
}
