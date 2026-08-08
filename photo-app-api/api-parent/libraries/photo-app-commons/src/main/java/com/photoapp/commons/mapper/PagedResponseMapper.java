package com.photoapp.commons.mapper;

import com.photoapp.commons.dto.PagedResponseDTO;
import org.mapstruct.Mapper;
import org.springframework.data.domain.Page;

import java.util.function.Function;

/**
 * Turns a {@code Page} of entities into the {@link PagedResponseDTO} every paginated endpoint
 * returns.
 *
 * <p>The one shared place that decides what a paginated response looks like. Before this existed,
 * four services each returned their repository's {@code Page} straight out of the controller, so
 * the wire contract was whatever {@code PageImpl} happened to serialise to that Spring version.
 */
@Mapper(componentModel = "spring")
public interface PagedResponseMapper {

    /*
        Both methods are `default` on purpose, and this is the one mapper in the project where
        MapStruct generates nothing.

        MapStruct works from concrete types at compile time. PagedResponseDTO<T> is generic over
        an element type that is only known at the call site, and Page<T> is an interface with no
        settable properties, so there is no <E, D> pair for MapStruct to resolve and no
        constructor or setters for it to write into. Asking it to try produces either an
        unmappable-target error or, worse, a mapping that silently drops `content`.

        It is still declared as a @Mapper rather than a static utility so it is a Spring bean like
        the other eight, injected the same way and mocked the same way in slice tests. The
        alternative - a static PagedResponseDTO.from(...) - is also available and is what this
        delegates to; use whichever the surrounding code already does.
     */

    /**
     * Maps and wraps in one step: the usual call from a service's {@code findAll}.
     *
     * @param page          a page of entities, straight from the repository
     * @param elementMapper the mapper method for one element, e.g. {@code userMapper::toDTO}
     * @param <E>           entity type
     * @param <D>           DTO type
     */
    default <E, D> PagedResponseDTO<D> toPagedResponse(Page<E> page, Function<E, D> elementMapper) {
        if (page == null) {
            throw new IllegalArgumentException("page must not be null");
        }
        if (elementMapper == null) {
            throw new IllegalArgumentException("elementMapper must not be null");
        }
        return PagedResponseDTO.from(page.map(elementMapper));
    }

    /**
     * Wraps a page whose elements are already DTOs.
     *
     * <p>For callers that mapped earlier for their own reasons — filtering on a DTO field, say —
     * and would otherwise have to pass {@code Function.identity()}.
     */
    default <D> PagedResponseDTO<D> toPagedResponse(Page<D> page) {
        return PagedResponseDTO.from(page);
    }
}
