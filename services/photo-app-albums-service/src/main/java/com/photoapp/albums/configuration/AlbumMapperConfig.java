package com.photoapp.albums.configuration;

import com.photoapp.albums.dto.CreateAlbumInputDTO;
import com.photoapp.entity.Album;
import org.modelmapper.ModelMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AlbumMapperConfig {

    @Bean
    public ModelMapper albumModelMapper(ModelMapper modelMapper) {
        modelMapper.typeMap(CreateAlbumInputDTO.class, Album.class)
                .addMappings(mapper -> {
                    mapper.skip(Album::setId);
                    mapper.skip(Album::setVersion);
                    mapper.skip(Album::setCreatedAt);
                    mapper.skip(Album::setUpdatedAt);
                });
        return modelMapper;
    }

}

