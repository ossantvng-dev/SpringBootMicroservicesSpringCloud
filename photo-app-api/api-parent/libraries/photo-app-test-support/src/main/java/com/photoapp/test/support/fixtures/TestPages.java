package com.photoapp.test.support.fixtures;

import com.photoapp.commons.dto.PagedResponseDTO;

import java.util.Arrays;
import java.util.List;

/**
 * {@link PagedResponseDTO} fixtures, for the four controller suites that stub a {@code findAll}.
 *
 * <p>Shared rather than repeated per module because all four services return the same wire shape
 * now, and that is the whole point of the type: if the shape changes again, it changes in one
 * place and every suite follows.
 *
 * <p>Before 2026-08-08 these stubs were {@code new PageImpl<>(List.of())}, which quietly made each
 * suite depend on Spring Data's serialisation of an implementation class.
 */
public final class TestPages {

    private TestPages() {
    }

    /** An empty page — the usual stub when a test only cares that the endpoint was reachable. */
    public static <T> PagedResponseDTO<T> emptyPagedResponse() {
        return PagedResponseDTO.<T>builder()
                .totalElements(0)
                .totalPages(0)
                .pageNumber(0)
                .pageSize(20)
                .content(List.of())
                .build();
    }

    /**
     * A single-page response holding the given rows.
     *
     * <p>{@code totalPages} is 1 rather than 0 even when the content is empty-adjacent, because a
     * caller that asked for rows and got some is on page one of one — the degenerate case belongs
     * in {@link #emptyPagedResponse()}.
     */
    @SafeVarargs
    public static <T> PagedResponseDTO<T> singlePage(T... content) {
        List<T> rows = Arrays.asList(content);
        return PagedResponseDTO.<T>builder()
                .totalElements(rows.size())
                .totalPages(1)
                .pageNumber(0)
                .pageSize(20)
                .content(rows)
                .build();
    }
}
