package com.photoapp.commons.util;

import com.photoapp.commons.dto.PaginationInputDTO;
import org.springframework.data.domain.Pageable;

import java.util.Map;

public class PaginationUtil {

    public static Pageable mapToPageable(Map<String, String> params) {
        PaginationInputDTO dto = new PaginationInputDTO();
        dto.setPage(Integer.parseInt(params.getOrDefault("page", String.valueOf(dto.getPage()))));
        dto.setSize(Integer.parseInt(params.getOrDefault("size", String.valueOf(dto.getSize()))));
        dto.setSortBy(params.getOrDefault("sortBy", dto.getSortBy()));
        dto.setDirection(params.getOrDefault("direction", dto.getDirection()));
        return dto.getPageable();
    }

}
