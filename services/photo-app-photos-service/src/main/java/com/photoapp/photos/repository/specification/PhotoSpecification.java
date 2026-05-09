package com.photoapp.photos.repository.specification;

import com.photoapp.photos.dto.PhotoFilterDTO;
import com.photoapp.photos.entity.Photo;
import com.photoapp.photos.entity.Photo_;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

public class PhotoSpecification {

    public static Specification<Photo> fromFilter(PhotoFilterDTO filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (filter.getAlbumId() != null) {
                predicates.add(cb.equal(root.get(Photo_.albumId), filter.getAlbumId()));
            }

            if (filter.getFileName() != null && !filter.getFileName().isBlank()) {
                predicates.add(
                        cb.like(cb.lower(root.get(Photo_.fileName)), "%" + filter.getFileName().toLowerCase() + "%")
                );
            }

            if (filter.getActivePhoto() != null) {
                predicates.add(cb.equal(root.get(Photo_.activePhoto), filter.getActivePhoto()));
            }

            if (filter.getCreatedStart() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get(Photo_.createdAt), filter.getCreatedStart()));
            }

            if (filter.getCreatedEnd() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get(Photo_.createdAt), filter.getCreatedEnd()));
            }

            if (filter.getUpdatedStart() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get(Photo_.updatedAt), filter.getUpdatedStart()));
            }

            if (filter.getUpdatedEnd() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get(Photo_.updatedAt), filter.getUpdatedEnd()));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
