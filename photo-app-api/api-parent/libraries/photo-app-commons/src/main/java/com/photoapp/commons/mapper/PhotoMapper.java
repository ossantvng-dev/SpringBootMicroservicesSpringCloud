package com.photoapp.commons.mapper;

import com.photoapp.commons.dto.photo.PhotoDTO;
import com.photoapp.entity.Photo;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface PhotoMapper {

    PhotoDTO toDTO(Photo photo);

}
