package com.photoapp.commons.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * The wire shape for every paginated endpoint in this system.
 *
 * <p>Flat and explicit: five fields, no nesting. That is a deliberate choice against both
 * alternatives Spring offers.
 *
 * <ul>
 *   <li>Returning {@code Page<T>} (i.e. {@code PageImpl}) directly serialises whatever the current
 *       Spring Data version happens to expose — {@code pageable}, {@code sort}, {@code first},
 *       {@code last}, {@code numberOfElements}, {@code empty}, and a nested {@code sort} inside a
 *       nested {@code pageable}. It is a serialised implementation class, not a contract, and
 *       Spring itself warns about it: <em>"Serializing PageImpl instances as-is is not
 *       supported"</em>. The shape has changed across Spring versions before.</li>
 *   <li>Spring's own {@code PagedModel} fixes the stability problem but nests the metadata under a
 *       {@code page} object, so every client reads {@code page.totalElements}. That is fine, and it
 *       is not what this project chose in Step 5.</li>
 * </ul>
 *
 * <p>What a client needs to paginate is exactly: how many rows exist, how many pages that is,
 * which page this is, how big a page is, and the rows. Everything else in {@code PageImpl} is
 * derivable from those five ({@code first} is {@code pageNumber == 0}; {@code numberOfElements} is
 * {@code content.size()}), so it is redundant on the wire and one more thing to keep stable.
 *
 * <p>It is also an ordinary bean with a no-arg constructor, which {@code Page} is not — that is
 * what lets the Feign clients deserialise a paged response without depending on Spring Cloud's
 * {@code pageJacksonModule} being registered.
 *
 * @param <T> the element type — a DTO, never an entity
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PagedResponseDTO<T> {

    /** Total rows matching the query across all pages, not just this one. */
    private long totalElements;

    /** Total pages available. Zero when nothing matched. */
    private int totalPages;

    /** Zero-based index of this page, echoing the {@code page} query parameter. */
    private int pageNumber;

    /** Requested page size, echoing the {@code size} query parameter. */
    private int pageSize;

    /** This page's rows. Empty, never null. */
    private List<T> content;

    /**
     * Builds a response from an already-mapped {@code Page}.
     *
     * <p>The argument is deliberately {@code Page<T>} of <em>DTOs</em>, not of entities. Services
     * map first ({@code repository.findAll(spec, pageable).map(mapper::toDTO)}) and wrap second,
     * which keeps entities from ever reaching a serialiser and keeps this class free of any
     * knowledge of mappers.
     *
     * @throws IllegalArgumentException if {@code page} is null — a null page is a programming
     *         error at the call site, and returning an empty response would hide it
     */
    public static <T> PagedResponseDTO<T> from(Page<T> page) {
        if (page == null) {
            throw new IllegalArgumentException("page must not be null");
        }
        return PagedResponseDTO.<T>builder()
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .pageNumber(page.getNumber())
                .pageSize(page.getSize())
                .content(List.copyOf(page.getContent()))
                .build();
    }
}
