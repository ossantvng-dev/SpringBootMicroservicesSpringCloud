package com.photoapp.albums.repository.specification;

import com.photoapp.albums.dto.AlbumFilterDTO;
import com.photoapp.entity.Album;
import com.photoapp.entity.Album_;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AlbumSpecification {

    public static Specification<Album> fromFilter(AlbumFilterDTO filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (filter.getAccountIds() != null && !filter.getAccountIds().isBlank()) {
                List<Long> accountIds = Arrays.stream(filter.getAccountIds().split(","))
                        .map(String::trim)
                        .map(Long::valueOf)
                        .toList();
                predicates.add(root.get(Album_.accountId).in(accountIds));
            }

            if (filter.getTitle() != null && !filter.getTitle().isBlank()) {
                predicates.add(cb.like(cb.lower(root.get(Album_.title)), "%" + filter.getTitle().toLowerCase() + "%"));
            }

            if (filter.getDescription() != null && !filter.getDescription().isBlank()) {
                predicates.add(cb.like(cb.lower(root.get(Album_.description)), "%" + filter.getDescription().toLowerCase() + "%"));
            }

            if (filter.getActiveAlbum() != null) {
                predicates.add(cb.equal(root.get(Album_.activeAlbum), filter.getActiveAlbum()));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
