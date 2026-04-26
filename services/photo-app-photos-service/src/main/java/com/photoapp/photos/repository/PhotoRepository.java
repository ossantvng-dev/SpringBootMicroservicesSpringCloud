package com.photoapp.photos.repository;

import com.photoapp.photos.entity.Photo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PhotoRepository extends JpaRepository<Photo, Long>, JpaSpecificationExecutor<Photo> {

    List<Photo> findByAlbumId(Long albumId);

    List<Photo> findByAlbumIdAndActivePhotoTrue(Long albumId);

    long countByAlbumIdAndActivePhotoTrue(Long albumId);

}
