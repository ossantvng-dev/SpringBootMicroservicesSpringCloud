package com.photoapp.photos.repository;

import com.photoapp.entity.Photo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PhotoRepository extends JpaRepository<Photo, Long>, JpaSpecificationExecutor<Photo> {

    void deleteByAlbumIdIn(List<Long> albumIds);

    long countByAlbumIdIn(List<Long> albumIds);

}
