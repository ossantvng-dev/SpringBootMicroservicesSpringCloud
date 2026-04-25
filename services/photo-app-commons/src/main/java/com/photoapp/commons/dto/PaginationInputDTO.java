package com.photoapp.commons.dto;

import jakarta.validation.constraints.Min;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@Data
@EqualsAndHashCode(callSuper = true)
public class PaginationInputDTO extends SortInputDTO {

    @Min(0)
    private int page = 0;

    private int size = 25;

    public Pageable getPageable() {
        return PageRequest.of(getPage(), getSize(), getSort());
    }
}
