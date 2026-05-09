package com.photoapp.photos.configuration;

import com.photoapp.photos.dto.CreatePhotoInputDTO;
import com.photoapp.photos.entity.Photo;
import org.modelmapper.ModelMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PhotoMapperConfig {

    @Bean
    public ModelMapper accountModelMapper(ModelMapper modelMapper) {
        modelMapper.typeMap(CreatePhotoInputDTO.class, Photo.class)
                .addMappings(mapper -> {
                    mapper.skip(Photo::setId);
                    mapper.skip(Photo::setVersion);
                    mapper.skip(Photo::setCreatedAt);
                    mapper.skip(Photo::setUpdatedAt);
                });
        return modelMapper;
    }

}
