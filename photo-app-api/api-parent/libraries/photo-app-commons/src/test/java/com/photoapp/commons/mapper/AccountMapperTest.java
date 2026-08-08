package com.photoapp.commons.mapper;

import com.photoapp.commons.dto.account.AccountDTO;
import com.photoapp.commons.dto.account.AccountTypeDTO;
import com.photoapp.commons.dto.account.CreateAccountInputDTO;
import com.photoapp.entity.Account;
import com.photoapp.entity.AccountType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AccountMapper} — the mapper this entire testing initiative traces back to.
 *
 * <p>Under ModelMapper, {@code Account.accountType} never reached {@code AccountDTO.accountTypeDTO}.
 * The names differ by a suffix, ModelMapper matched on name, found nothing, and set null. Nothing
 * failed: the endpoint returned 200 with {@code "accountTypeDTO": null}, and the only symptom was
 * a field that was always empty. Step 5 migrated to MapStruct with
 * {@code unmappedTargetPolicy = ReportingPolicy.ERROR}, which turns that class of mistake into a
 * compile error.
 *
 * <p>These tests are the runtime layer on top of that. The compile-time policy catches an
 * <em>unmapped</em> target; it cannot catch a target mapped to the <em>wrong source</em>, which is
 * the same silent-wrong-data outcome. Only an assertion on values does that.
 */
class AccountMapperTest {

    private final AccountMapper mapper = new AccountMapperImpl();

    private static Account anAccount() {
        Account account = Account.builder()
                .userId(42L)
                .accountName("ada-main")
                .accountType(AccountType.PREMIUM)
                .activeAccount(true)
                .build();
        account.setId(7L);
        account.setVersion(3L);
        return account;
    }

    /**
     * THE REGRESSION GUARD. Verifies {@code accountType} reaches {@code accountTypeDTO}.
     *
     * <p>The single assertion this whole initiative exists for. It is asserted on its own, before
     * the full-field test below, so that a failure names the field rather than burying it among
     * seven other comparisons.
     */
    @Test
    @DisplayName("accountType → accountTypeDTO, the name mismatch that shipped as null")
    void accountTypeReachesAccountTypeDto() {
        AccountDTO dto = mapper.toDTO(anAccount());

        assertThat(dto.getAccountTypeDTO())
                .as("This is the ModelMapper defect. A null here means the @Mapping(source = "
                        + "\"accountType\", target = \"accountTypeDTO\") pairing was lost, and every "
                        + "account in the API is missing its type again — with a 200 status.")
                .isEqualTo(AccountTypeDTO.PREMIUM);
    }

    /**
     * Verifies every {@code AccountDTO} field, including the four inherited from {@code BaseEntity}.
     *
     * <p>{@code id} and {@code version} are worth asserting explicitly: they live on
     * {@code BaseEntity}, not on {@code Account}, and MapStruct resolves them through the inherited
     * getters. A change to that hierarchy would break them while leaving the declared fields fine.
     */
    @Test
    void everyFieldMapsToTheDto() {
        AccountDTO dto = mapper.toDTO(anAccount());

        assertThat(dto.getId()).isEqualTo(7L);
        assertThat(dto.getVersion()).isEqualTo(3L);
        assertThat(dto.getUserId()).isEqualTo(42L);
        assertThat(dto.getAccountName()).isEqualTo("ada-main");
        assertThat(dto.getAccountTypeDTO()).isEqualTo(AccountTypeDTO.PREMIUM);
        assertThat(dto.getActiveAccount()).isTrue();
        assertThat(dto.getCreatedAt()).isNotNull();
        assertThat(dto.getUpdatedAt()).isNotNull();
    }

    /**
     * ROUND TRIP. Verifies entity → DTO → entity preserves the fields that exist on both sides.
     *
     * <p>The runtime safety net asked for in the Phase 5 brief. A one-way test can be satisfied by a
     * mapper that maps A→B correctly and B→A onto the wrong field; going back and comparing catches
     * a transposition that neither direction shows alone. {@code accountName} and
     * {@code accountType} are both single-valued and both settable in each direction, so a swap
     * between them would survive a one-way assertion and die here.
     *
     * <p>{@code id}, {@code version} and the timestamps are deliberately <em>not</em> compared: the
     * return leg goes through {@code CreateAccountInputDTO}, which has no such fields by design —
     * identity and auditing belong to the persistence layer, not to a create request.
     */
    @Test
    @DisplayName("round trip: entity → DTO → entity keeps the shared fields intact")
    void roundTripPreservesTheSharedFields() {
        Account original = anAccount();

        AccountDTO dto = mapper.toDTO(original);
        CreateAccountInputDTO input = new CreateAccountInputDTO();
        input.setUserId(dto.getUserId());
        input.setAccountName(dto.getAccountName());
        input.setAccountTypeDTO(dto.getAccountTypeDTO());

        Account back = mapper.toEntity(input);

        assertThat(back.getUserId()).isEqualTo(original.getUserId());
        assertThat(back.getAccountName()).isEqualTo(original.getAccountName());
        assertThat(back.getAccountType())
                .as("the return leg of the accountType/accountTypeDTO pairing")
                .isEqualTo(original.getAccountType());
    }

    /**
     * Verifies {@code toEntity} leaves {@code activeAccount} at the builder's default of true,
     * rather than mapping it to null.
     *
     * <p>{@code @Mapping(target = "activeAccount", ignore = true)} is easy to read as "this field
     * does not matter". It matters a great deal: an account created with a null
     * {@code activeAccount} fails every {@code !account.getActiveAccount()} guard in the system with
     * a NullPointerException, and those guards are the ownership checks. The ignore is only safe
     * because Lombok's {@code @Builder.Default} fills it in, and that coupling is invisible in
     * either file alone.
     */
    @Test
    @DisplayName("ignored activeAccount falls back to @Builder.Default(true), not null")
    void toEntityLeavesActiveAccountAtItsBuilderDefault() {
        CreateAccountInputDTO input = new CreateAccountInputDTO();
        input.setUserId(42L);
        input.setAccountName("new-account");
        input.setAccountTypeDTO(AccountTypeDTO.BASIC);

        Account entity = mapper.toEntity(input);

        assertThat(entity.getActiveAccount())
                .as("null here NPEs every activeAccount guard in accounts, albums and photos")
                .isTrue();
        assertThat(entity.getAccountType()).isEqualTo(AccountType.BASIC);
        assertThat(entity.getUserId()).isEqualTo(42L);
        assertThat(entity.getAccountName()).isEqualTo("new-account");
    }

    /**
     * Verifies the {@code AccountType} enum survives both directions, for every constant.
     *
     * <p>Parameterised over the enum rather than over a chosen value, so adding a third account type
     * — which the album-limit configuration is already shaped for — is covered the moment it is
     * declared. The two enums are structurally identical and converted by name, so a constant added
     * to one and not the other fails here rather than at runtime on the first account of that type.
     */
    @ParameterizedTest(name = "{0} survives entity ↔ DTO")
    @EnumSource(AccountType.class)
    void everyAccountTypeConstantSurvivesBothDirections(AccountType type) {
        AccountTypeDTO asDto = mapper.toDTO(type);
        assertThat(asDto.name()).isEqualTo(type.name());
        assertThat(mapper.toEntity(asDto)).isEqualTo(type);
    }

    /** Verifies a null entity maps to a null DTO rather than throwing. */
    @Test
    void nullInputsProduceNullOutputs() {
        assertThat(mapper.toDTO((Account) null)).isNull();
        assertThat(mapper.toEntity((CreateAccountInputDTO) null)).isNull();
        assertThat(mapper.toDTO((AccountType) null)).isNull();
        assertThat(mapper.toEntity((AccountTypeDTO) null)).isNull();
    }

    /**
     * Verifies a null {@code accountType} on the entity produces a null DTO value rather than
     * throwing.
     *
     * <p>Not reachable through the API — the column is non-null — but it is reachable from a test
     * or a partially-built entity, and a mapper that threw here would turn a data problem into a
     * 500 from a code path that only reads.
     */
    @Test
    void aNullNestedEnumDoesNotThrow() {
        Account account = Account.builder().userId(1L).accountName("x").accountType(null).build();

        assertThat(mapper.toDTO(account).getAccountTypeDTO()).isNull();
    }
}
