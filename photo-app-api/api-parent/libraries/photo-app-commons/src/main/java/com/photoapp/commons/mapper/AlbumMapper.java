package com.photoapp.commons.mapper;

import com.photoapp.commons.dto.album.AlbumDTO;
import com.photoapp.entity.Album;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface AlbumMapper {

    AlbumDTO toDTO(Album album);

}
