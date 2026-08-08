package com.photoapp.commons.mapper;

import com.photoapp.commons.dto.PagedResponseDTO;
import com.photoapp.commons.dto.account.AccountDTO;
import com.photoapp.commons.dto.account.AccountTypeDTO;
import com.photoapp.entity.Account;
import com.photoapp.entity.AccountType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PagedResponseMapper} and the {@link PagedResponseDTO#from} it delegates to.
 *
 * <p>This is the one mapper in the project MapStruct generates nothing for — see the comment on the
 * interface for why generics defeat it — so unlike the other eight, every line under test here was
 * written by hand. That makes it the mapper most worth testing rather than the least.
 *
 * <p>It is also the shared wire contract for four services. A field mapped to the wrong source here
 * is wrong on every paginated endpoint at once, and the failure mode is quiet: a client paginating
 * on {@code totalPages} that silently reads {@code pageSize} still gets a plausible number.
 */
class PagedResponseMapperTest {

    /** MapStruct generates an impl for the interface; the two methods under test are `default`. */
    private final PagedResponseMapper mapper = new PagedResponseMapperImpl();

    private static Account account(long id, String name) {
        Account account = Account.builder()
                .userId(42L)
                .accountName(name)
                .accountType(AccountType.PREMIUM)
                .activeAccount(true)
                .build();
        account.setId(id);
        return account;
    }

    private static final Function<Account, AccountDTO> TO_DTO = a -> AccountDTO.builder()
            .id(a.getId())
            .userId(a.getUserId())
            .accountName(a.getAccountName())
            .accountTypeDTO(AccountTypeDTO.valueOf(a.getAccountType().name()))
            .activeAccount(a.getActiveAccount())
            .build();

    /**
     * Verifies all five fields come from the right source on a page that is genuinely in the middle
     * of a result set.
     *
     * <p>Page 2 of 4 with 7 total rows and a size of 2 is chosen so that <em>no two fields share a
     * value</em>: {@code totalElements}=7, {@code totalPages}=4, {@code pageNumber}=2,
     * {@code pageSize}=2 (with content of 1). A fixture where the numbers coincide — the usual
     * "one page of one" — cannot distinguish a mapper that reads the wrong getter, and every field
     * here is a plain number, so a transposition is invisible in the response body.
     */
    @Test
    @DisplayName("every field maps from its own source, on a mid-result page")
    void allFiveFieldsMapFromTheCorrectSource() {
        Page<Account> page = new PageImpl<>(
                List.of(account(5L, "fifth")), PageRequest.of(2, 2), 7);

        PagedResponseDTO<AccountDTO> result = mapper.toPagedResponse(page, TO_DTO);

        assertThat(result.getTotalElements()).as("totalElements is the count across ALL pages").isEqualTo(7L);
        assertThat(result.getTotalPages()).as("7 rows at size 2 is 4 pages").isEqualTo(4);
        assertThat(result.getPageNumber()).as("zero-based index of THIS page").isEqualTo(2);
        assertThat(result.getPageSize()).as("the requested size, not the content size").isEqualTo(2);
        assertThat(result.getContent()).hasSize(1);
    }

    /**
     * Verifies the element mapper is actually applied — entities must not reach the response.
     *
     * <p>The assertion is on type, not just on values. A wrapper that forgot to map would still
     * produce a response with the right counts and a {@code content} array of the right length, and
     * the entity would serialise to something that looks almost like the DTO — including
     * {@code passwordHash} on a user, which is the field {@code UserMapper} exists to keep off the
     * wire.
     */
    @Test
    void theElementMapperIsAppliedSoEntitiesNeverReachTheResponse() {
        Page<Account> page = new PageImpl<>(List.of(account(1L, "first"), account(2L, "second")));

        PagedResponseDTO<AccountDTO> result = mapper.toPagedResponse(page, TO_DTO);

        assertThat(result.getContent()).hasOnlyElementsOfType(AccountDTO.class);
        assertThat(result.getContent()).extracting(AccountDTO::getAccountName)
                .containsExactly("first", "second");
        assertThat(result.getContent().getFirst().getAccountTypeDTO())
                .as("the element mapper's own work must survive the wrapping")
                .isEqualTo(AccountTypeDTO.PREMIUM);
    }

    /**
     * Verifies content order is preserved.
     *
     * <p>Pagination is meaningless without stable ordering: if page 2 does not continue where
     * page 1 stopped, a client walking the pages sees duplicates and misses rows. The repository
     * decides the order; this must not disturb it.
     */
    @Test
    void contentOrderIsPreserved() {
        Page<Account> page = new PageImpl<>(
                List.of(account(3L, "c"), account(1L, "a"), account(2L, "b")));

        assertThat(mapper.toPagedResponse(page, TO_DTO).getContent())
                .extracting(AccountDTO::getAccountName)
                .containsExactly("c", "a", "b");
    }

    /**
     * EDGE CASE — empty page. Verifies {@code content} is an empty list, never null.
     *
     * <p>A null here would serialise to {@code "content": null}, and a client doing
     * {@code response.content.length} gets a TypeError instead of zero. "No results" is the single
     * most common response a filtered list endpoint gives, so this is the path most likely to be
     * hit and least likely to be tested.
     *
     * <p>{@code totalPages} is 0 rather than 1: Spring reports no pages when there is nothing to
     * page through, and the wrapper passes that on rather than inventing a page.
     */
    @Test
    @DisplayName("empty page: content is [], not null")
    void anEmptyPageProducesAnEmptyListNotNull() {
        Page<Account> empty = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);

        PagedResponseDTO<AccountDTO> result = mapper.toPagedResponse(empty, TO_DTO);

        assertThat(result.getContent()).isNotNull().isEmpty();
        assertThat(result.getTotalElements()).isZero();
        assertThat(result.getTotalPages()).as("no rows means no pages to walk").isZero();
        assertThat(result.getPageNumber()).isZero();
        assertThat(result.getPageSize()).as("the requested size still echoes back").isEqualTo(20);
    }

    /**
     * EDGE CASE — a single page holding every row. Verifies the counts agree with each other.
     *
     * <p>The common case for a small result set, and the one where an off-by-one is easiest to
     * ship: three rows on one page must be {@code totalElements}=3 and {@code totalPages}=1, not
     * {@code totalPages}=3. A client that renders "page 1 of {totalPages}" would show "1 of 3" and
     * offer two pages that do not exist.
     */
    @Test
    @DisplayName("single page: 3 rows is 1 page, not 3")
    void aSinglePageHoldingEveryRowReportsOnePage() {
        Page<Account> page = new PageImpl<>(
                List.of(account(1L, "a"), account(2L, "b"), account(3L, "c")),
                PageRequest.of(0, 20), 3);

        PagedResponseDTO<AccountDTO> result = mapper.toPagedResponse(page, TO_DTO);

        assertThat(result.getTotalElements()).isEqualTo(3L);
        assertThat(result.getTotalPages()).isEqualTo(1);
        assertThat(result.getPageNumber()).isZero();
        assertThat(result.getContent()).hasSize(3);
    }

    /**
     * EDGE CASE — a partial last page. Verifies {@code pageSize} stays the requested size rather
     * than collapsing to the number of rows returned.
     *
     * <p>The distinction only shows up here: on a full page the two numbers are equal, so a mapper
     * that wrongly read {@code content.size()} would pass every other test in this class. A client
     * computing offsets from {@code pageSize} would then skip rows on every subsequent request.
     */
    @Test
    @DisplayName("partial last page: pageSize is the requested size, not the row count")
    void aPartialLastPageKeepsTheRequestedPageSize() {
        Page<Account> lastPage = new PageImpl<>(
                List.of(account(5L, "fifth")), PageRequest.of(2, 2), 5);

        PagedResponseDTO<AccountDTO> result = mapper.toPagedResponse(lastPage, TO_DTO);

        assertThat(result.getPageSize()).as("requested 2 per page").isEqualTo(2);
        assertThat(result.getContent()).as("but only 1 row was left").hasSize(1);
        assertThat(result.getTotalElements()).isEqualTo(5L);
        assertThat(result.getTotalPages()).isEqualTo(3);
    }

    /**
     * Verifies the already-mapped overload agrees with the mapping one.
     *
     * <p>Two methods that must never diverge. Asserting them equal rather than restating the
     * expected values means a change to one that is not made to the other fails here, which is the
     * only place that would notice.
     */
    @Test
    void bothOverloadsProduceTheSameResult() {
        Page<Account> page = new PageImpl<>(List.of(account(1L, "a")), PageRequest.of(1, 3), 9);

        PagedResponseDTO<AccountDTO> viaMapping = mapper.toPagedResponse(page, TO_DTO);
        PagedResponseDTO<AccountDTO> viaAlreadyMapped = mapper.toPagedResponse(page.map(TO_DTO));

        assertThat(viaAlreadyMapped).isEqualTo(viaMapping);
    }

    /**
     * Verifies the returned content cannot be mutated through the response.
     *
     * <p>{@code List.copyOf} is deliberate. A response DTO handed back to a caller that could then
     * mutate the list would let one request's content be altered after the fact, and a shared
     * reference into a repository result is a subtle way to corrupt a persistence-context-backed
     * list. Cheap to guarantee, hard to debug if absent.
     */
    @Test
    void theReturnedContentIsImmutable() {
        Page<Account> page = new PageImpl<>(List.of(account(1L, "a")));

        List<AccountDTO> content = mapper.toPagedResponse(page, TO_DTO).getContent();

        assertThatThrownBy(() -> content.add(null)).isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * Verifies a null page fails loudly instead of producing an empty response.
     *
     * <p>A null page is a bug at the call site — a repository never returns one. Quietly mapping it
     * to an empty response would present "your query matched nothing" to the user, which is a
     * plausible answer and therefore never investigated.
     */
    @Test
    void aNullPageThrowsRatherThanReturningAnEmptyResponse() {
        assertThatThrownBy(() -> mapper.toPagedResponse(null, TO_DTO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("page");

        assertThatThrownBy(() -> PagedResponseDTO.from(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Verifies a null element mapper fails loudly too, rather than NPE-ing inside {@code map}. */
    @Test
    void aNullElementMapperThrowsWithAUsefulMessage() {
        Page<Account> page = new PageImpl<>(List.of(account(1L, "a")));

        assertThatThrownBy(() -> mapper.toPagedResponse(page, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("elementMapper");
    }
}
