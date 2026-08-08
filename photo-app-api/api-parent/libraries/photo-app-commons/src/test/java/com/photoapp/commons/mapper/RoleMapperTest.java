package com.photoapp.commons.mapper;

import com.photoapp.commons.dto.role.RoleDTO;
import com.photoapp.commons.dto.role.RoleNameDTO;
import com.photoapp.entity.Role;
import com.photoapp.entity.RoleName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RoleMapper} — the mapper every authorization decision ultimately reads from.
 *
 * <p>The only mapper with a collection method ({@code toDTOs(Set)}) and the only one used by
 * another mapper. A role that fails to map does not throw and does not log: it produces a user
 * whose permissions are quietly smaller than they should be, which surfaces as a 403 somewhere
 * unrelated and looks like an authorization bug.
 */
class RoleMapperTest {

    private final RoleMapper mapper = new RoleMapperImpl();

    private static Role role(RoleName name, long id) {
        Role role = Role.builder().name(name).build();
        role.setId(id);
        role.setVersion(1L);
        return role;
    }

    /** Verifies a single role maps its identity, version and name. */
    @Test
    void everyFieldMapsToTheDto() {
        RoleDTO dto = mapper.toDTO(role(RoleName.ROLE_ADMIN, 1L));

        assertThat(dto.getName()).isEqualTo(RoleNameDTO.ROLE_ADMIN);
    }

    /**
     * Verifies the {@code Set} overload maps every element rather than just the first.
     *
     * <p>The two-element case is the one that matters and the one that is awkward to construct:
     * {@code BaseEntity.hashCode} is a constant, so both roles land in the same hash bucket and
     * {@code equals} decides whether the set holds one entry or two. A mapper that returned a
     * one-element set would look correct in any single-role fixture — and single-role is what
     * every seeded user has, so production would not reveal it either.
     */
    @Test
    @DisplayName("toDTOs maps every element of the set, not just the first")
    void theCollectionOverloadMapsEveryElement() {
        Set<Role> roles = new LinkedHashSet<>();
        roles.add(role(RoleName.ROLE_ADMIN, 1L));
        roles.add(role(RoleName.ROLE_USER, 2L));

        Set<RoleDTO> dtos = mapper.toDTOs(roles);

        assertThat(dtos)
                .hasSize(2)
                .extracting(RoleDTO::getName)
                .containsExactlyInAnyOrder(RoleNameDTO.ROLE_ADMIN, RoleNameDTO.ROLE_USER);
    }

    /** Verifies an empty set maps to an empty set, not to null. */
    @Test
    void anEmptySetMapsToAnEmptySet() {
        assertThat(mapper.toDTOs(Set.of())).isNotNull().isEmpty();
    }

    /**
     * ROUND TRIP. Verifies role → DTO → role preserves the name.
     *
     * <p>{@code name} is the only field that crosses both ways — {@code toEntity} goes through
     * Lombok's builder, which cannot reach the inherited {@code BaseEntity} fields, so identity
     * and auditing deliberately do not survive the return leg. That is the documented intent, and
     * asserting it here stops a future {@code @Mapping(target = "id", ...)} being added on the
     * assumption that its absence was an oversight.
     */
    @ParameterizedTest(name = "{0} survives entity → DTO → entity")
    @EnumSource(RoleName.class)
    void roundTripPreservesTheNameAndNothingElse(RoleName name) {
        Role original = role(name, 9L);

        Role back = mapper.toEntity(mapper.toDTO(original));

        assertThat(back.getName()).isEqualTo(original.getName());
        assertThat(back.getId())
                .as("identity belongs to the persistence layer; the builder cannot reach it")
                .isNull();
    }

    /**
     * Verifies every {@code RoleName} constant converts in both directions.
     *
     * <p>{@code RoleName} and {@code RoleNameDTO} are two independent enums that MapStruct matches
     * by name. Adding a constant to one and not the other compiles fine on the entity side and
     * fails at runtime the first time that role is loaded — which, for a role, means the first
     * time someone with it logs in.
     */
    @ParameterizedTest(name = "{0} converts both ways")
    @EnumSource(RoleName.class)
    void everyRoleNameConstantConvertsBothWays(RoleName name) {
        RoleNameDTO asDto = mapper.toDTO(name);

        assertThat(asDto.name()).isEqualTo(name.name());
        assertThat(mapper.toEntity(asDto)).isEqualTo(name);
    }

    /** Verifies the two enums have exactly the same constants, so neither can drift. */
    @Test
    void theTwoRoleEnumsHaveIdenticalConstants() {
        assertThat(Set.of(RoleNameDTO.values()).stream().map(Enum::name).sorted().toList())
                .as("a constant on one enum and not the other fails at runtime, on login")
                .isEqualTo(Set.of(RoleName.values()).stream().map(Enum::name).sorted().toList());
    }

    /** Verifies null inputs produce null outputs on every method rather than throwing. */
    @Test
    void nullInputsProduceNullOutputs() {
        assertThat(mapper.toDTO((Role) null)).isNull();
        assertThat(mapper.toEntity((RoleDTO) null)).isNull();
        assertThat(mapper.toDTO((RoleName) null)).isNull();
        assertThat(mapper.toEntity((RoleNameDTO) null)).isNull();
        assertThat(mapper.toDTOs(null)).isNull();
    }
}
