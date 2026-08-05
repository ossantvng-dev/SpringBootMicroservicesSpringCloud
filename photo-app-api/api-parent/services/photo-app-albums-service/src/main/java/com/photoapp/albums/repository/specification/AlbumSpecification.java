package com.photoapp.albums.repository.specification;

import com.photoapp.albums.dto.AlbumFilterDTO;
import com.photoapp.commons.exception.ApplicationException;
import com.photoapp.entity.Album;
import com.photoapp.entity.Album_;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;

public class AlbumSpecification {

    public static Specification<Album> fromFilter(AlbumFilterDTO filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (filter.getAccountIds() != null && !filter.getAccountIds().isBlank()) {
                predicates.add(root.get(Album_.accountId).in(parseAccountIds(filter.getAccountIds())));
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

    /*
        accountIds arrives as a raw comma-separated query-string value, so every element is
        client-controlled. Long::valueOf previously threw NumberFormatException from inside
        the Specification lambda - which JPA evaluates during query execution, so Spring
        translated it into a DataAccessException and GlobalExceptionHandler reported
        "Database error occurred" with a 500. Nothing was wrong with the database: the
        caller had sent a non-numeric id.

        Parsing eagerly here means a malformed id fails as a 400 before any query runs.
     */
    public static List<Long> parseAccountIds(String raw) {
        List<Long> accountIds = new ArrayList<>();
        for (String token : raw.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                accountIds.add(Long.valueOf(trimmed));
            } catch (NumberFormatException ex) {
                throw new ApplicationException(
                        "Invalid 'accountIds' parameter: '" + trimmed + "' is not a number",
                        HttpStatus.BAD_REQUEST
                );
            }
        }
        if (accountIds.isEmpty()) {
            throw new ApplicationException(
                    "Invalid 'accountIds' parameter: no account ids supplied",
                    HttpStatus.BAD_REQUEST
            );
        }
        return accountIds;
    }
}
