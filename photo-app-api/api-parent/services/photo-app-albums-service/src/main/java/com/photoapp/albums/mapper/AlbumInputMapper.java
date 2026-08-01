package com.photoapp.albums.mapper;

import com.photoapp.albums.dto.CreateAlbumInputDTO;
import com.photoapp.entity.Album;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/*
    Lives here rather than in photo-app-commons because CreateAlbumInputDTO is owned by
    this service; commons cannot depend on it without a cycle.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface AlbumInputMapper {

    // activeAlbum keeps its @Builder.Default of true
    @Mapping(target = "activeAlbum", ignore = true)
    Album toEntity(CreateAlbumInputDTO createAlbumInputDTO);

}
