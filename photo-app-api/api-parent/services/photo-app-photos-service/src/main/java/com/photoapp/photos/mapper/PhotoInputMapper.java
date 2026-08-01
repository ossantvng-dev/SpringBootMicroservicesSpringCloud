package com.photoapp.photos.mapper;

import com.photoapp.entity.Photo;
import com.photoapp.photos.dto.CreatePhotoInputDTO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/*
    Lives here rather than in photo-app-commons because CreatePhotoInputDTO is owned by
    this service; commons cannot depend on it without a cycle.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface PhotoInputMapper {

    // activePhoto keeps its @Builder.Default of true
    @Mapping(target = "activePhoto", ignore = true)
    Photo toEntity(CreatePhotoInputDTO createPhotoInputDTO);

}
