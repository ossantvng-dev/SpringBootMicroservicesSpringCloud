package com.photoapp.commons.dto;

import lombok.Data;
import org.springframework.data.domain.Sort;

@Data
public class SortInputDTO {

    private String sortBy = "id";

    private String direction = "asc";

    public Sort getSort() {
        return Sort.by(Sort.Direction.fromString(direction), sortBy);
    }
}


