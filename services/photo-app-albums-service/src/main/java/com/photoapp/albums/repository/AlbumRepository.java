package com.photoapp.albums.repository;

import com.photoapp.entity.Album;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AlbumRepository extends JpaRepository<Album, Long>, JpaSpecificationExecutor<Album> {

    void deleteByAccountIdIn(List<Long> accountIds);

    long countByAccountIdAndActiveAlbumTrue(Long accountId);

    long countByAccountId(Long accountId);

    @Query("SELECT a.id FROM Album a WHERE a.accountId IN :accountIds")
    List<Long> findIdsByAccountIdIn(List<Long> accountIds);

}
