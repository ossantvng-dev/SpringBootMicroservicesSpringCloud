package com.photoapp.commons.util;

import com.photoapp.commons.dto.PaginationInputDTO;
import com.photoapp.commons.exception.ApplicationException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;

import java.util.Map;

/*
    Every value here comes straight off the query string, so every parse is a place a
    client can crash the request. Previously they all threw unchecked out of this method
    and were caught by GlobalExceptionHandler's Exception catch-all, turning a malformed
    query parameter into a 500 - the same defect class as the Step 8 authorization bug,
    where a client mistake was reported as a server fault.

    Anything a caller can get wrong is now a 400 that names the parameter.
 */
public class PaginationUtil {

    private PaginationUtil() {
    }

    public static Pageable mapToPageable(Map<String, String> params) {
        PaginationInputDTO dto = new PaginationInputDTO();

        dto.setPage(parseInt(params, "page", dto.getPage(), 0));
        dto.setSize(parseInt(params, "size", dto.getSize(), 1));
        dto.setSortBy(params.getOrDefault("sortBy", dto.getSortBy()));
        dto.setDirection(parseDirection(params, dto.getDirection()));

        return dto.getPageable();
    }

    private static int parseInt(Map<String, String> params, String name, int defaultValue, int minimum) {
        String raw = params.get(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }

        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            throw new ApplicationException(
                    "Invalid '" + name + "' parameter: '" + raw + "' is not a number",
                    HttpStatus.BAD_REQUEST
            );
        }

        // PageRequest.of rejects these itself, but with an IllegalArgumentException that
        // would reach the catch-all as a 500. Checking here keeps it a 400.
        if (value < minimum) {
            throw new ApplicationException(
                    "Invalid '" + name + "' parameter: must be " + minimum + " or greater, was " + value,
                    HttpStatus.BAD_REQUEST
            );
        }
        return value;
    }

    private static String parseDirection(Map<String, String> params, String defaultValue) {
        String raw = params.get("direction");
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        // Sort.Direction.fromString throws IllegalArgumentException on anything else.
        try {
            Sort.Direction.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new ApplicationException(
                    "Invalid 'direction' parameter: '" + raw + "' must be 'asc' or 'desc'",
                    HttpStatus.BAD_REQUEST
            );
        }
        return raw.trim();
    }

}
