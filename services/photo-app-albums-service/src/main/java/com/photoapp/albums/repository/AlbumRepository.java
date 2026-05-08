package com.photoapp.albums.repository;

import com.photoapp.albums.entity.Album;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AlbumRepository extends JpaRepository<Album, Long>, JpaSpecificationExecutor<Album> {

    void deleteByAccountIdIn(List<Long> accountIds);

    long countByAccountIdAndActiveAlbumTrue(Long accountId);

}
