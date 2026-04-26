package com.photoapp.photos.repository.specification;

import com.photoapp.photos.dto.PhotoFilterDTO;
import com.photoapp.photos.entity.Photo;
import com.photoapp.photos.entity.Photo_;
import org.springframework.data.jpa.domain.Specification;

public class PhotoSpecification {

    public static Specification<Photo> fromFilter(PhotoFilterDTO filter) {
        return (root, query, cb) -> {
            var predicates = cb.conjunction();

            if (filter.getAlbumId() != null) {
                predicates.getExpressions().add(cb.equal(root.get(Photo_.albumId), filter.getAlbumId()));
            }

            if (filter.getFileName() != null && !filter.getFileName().isBlank()) {
                predicates.getExpressions().add(
                        cb.like(cb.lower(root.get(Photo_.fileName)), "%" + filter.getFileName().toLowerCase() + "%")
                );
            }

            if (filter.getActivePhoto() != null) {
                predicates.getExpressions().add(cb.equal(root.get(Photo_.activePhoto), filter.getActivePhoto()));
            }

            if (filter.getCreatedStart() != null) {
                predicates.getExpressions().add(cb.greaterThanOrEqualTo(root.get(Photo_.createdAt), filter.getCreatedStart()));
            }

            if (filter.getCreatedEnd() != null) {
                predicates.getExpressions().add(cb.lessThanOrEqualTo(root.get(Photo_.createdAt), filter.getCreatedEnd()));
            }

            if (filter.getUpdatedStart() != null) {
                predicates.getExpressions().add(cb.greaterThanOrEqualTo(root.get(Photo_.updatedAt), filter.getUpdatedStart()));
            }

            if (filter.getUpdatedEnd() != null) {
                predicates.getExpressions().add(cb.lessThanOrEqualTo(root.get(Photo_.updatedAt), filter.getUpdatedEnd()));
            }

            return predicates;
        };
    }
}
