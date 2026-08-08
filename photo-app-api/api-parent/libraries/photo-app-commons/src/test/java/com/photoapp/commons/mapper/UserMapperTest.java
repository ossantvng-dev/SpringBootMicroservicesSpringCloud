package com.photoapp.commons.mapper;

import com.photoapp.commons.dto.role.RoleDTO;
import com.photoapp.commons.dto.role.RoleNameDTO;
import com.photoapp.commons.dto.user.UserDTO;
import com.photoapp.entity.Role;
import com.photoapp.entity.RoleName;
import com.photoapp.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link UserMapper} — one method, and the most security-relevant mapping in the project.
 *
 * <p>It is the only mapper whose correctness is a <em>confidentiality</em> property rather than a
 * data-fidelity one: {@code User.passwordHash} must not reach {@code UserDTO}. That is enforced
 * structurally — the field simply does not exist on the DTO — which is stronger than an
 * {@code ignore}, and {@link #userDtoHasNoPasswordField} asserts the structure so that adding the
 * field back is a test failure rather than a quiet leak on every user endpoint.
 *
 * <p>It also composes: {@code uses = RoleMapper.class} delegates the nested role set.
 */
class UserMapperTest {

    /*
        MapStruct's Spring componentModel injects a `uses` mapper into an @Autowired FIELD, not
        through a constructor, so the generated UserMapperImpl has only a no-arg constructor and
        a null roleMapper until Spring populates it. Setting it by reflection keeps this a plain
        unit test - starting a context here would mean an application class this library does not
        have, to test one method.
     */
    private final UserMapper mapper = newUserMapper();

    private static UserMapper newUserMapper() {
        UserMapperImpl impl = new UserMapperImpl();
        try {
            Field roleMapper = UserMapperImpl.class.getDeclaredField("roleMapper");
            roleMapper.setAccessible(true);
            roleMapper.set(impl, new RoleMapperImpl());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "UserMapperImpl no longer has an injectable 'roleMapper' field - MapStruct's "
                            + "generation strategy changed, so check how `uses` is wired now", e);
        }
        return impl;
    }

    private static Role role(RoleName name) {
        Role role = Role.builder().name(name).build();
        role.setId(name == RoleName.ROLE_ADMIN ? 1L : 2L);
        return role;
    }

    private static User aUser(Set<Role> roles) {
        User user = User.builder()
                .firstName("Ada")
                .lastName("Lovelace")
                .username("ada")
                .email("ada@photoapp.com")
                .passwordHash("$2a$12$averysecrethash")
                .activeUser(true)
                .roles(roles)
                .build();
        user.setId(42L);
        user.setVersion(3L);
        return user;
    }

    /** Verifies every {@code UserDTO} field is populated from its matching entity field. */
    @Test
    void everyFieldMapsToTheDto() {
        UserDTO dto = mapper.toDTO(aUser(Set.of(role(RoleName.ROLE_USER))));

        assertThat(dto.getId()).isEqualTo(42L);
        assertThat(dto.getVersion()).isEqualTo(3L);
        assertThat(dto.getFirstName()).isEqualTo("Ada");
        assertThat(dto.getLastName()).isEqualTo("Lovelace");
        assertThat(dto.getUsername()).isEqualTo("ada");
        assertThat(dto.getEmail()).isEqualTo("ada@photoapp.com");
        assertThat(dto.getActiveUser()).isTrue();
        assertThat(dto.getCreatedAt()).isNotNull();
        assertThat(dto.getUpdatedAt()).isNotNull();
    }

    /**
     * THE SECURITY PROPERTY. Verifies {@code UserDTO} has no password field at all.
     *
     * <p>Asserted by reflection over the DTO's declared fields, not by checking a value, because
     * there is no value to check — and that is the point. The protection is structural: MapStruct
     * cannot map a target that does not exist. If someone adds {@code passwordHash} to
     * {@code UserDTO} for convenience, MapStruct will map it automatically and silently, and every
     * user endpoint starts returning BCrypt hashes with a 200.
     *
     * <p>The scan covers any field whose name suggests a credential rather than
     * {@code passwordHash} exactly, so {@code password}, {@code passwordHash} or {@code pwd} all
     * fail it.
     */
    @Test
    @DisplayName("UserDTO structurally cannot carry a password")
    void userDtoHasNoPasswordField() {
        String[] offenders = Arrays.stream(UserDTO.class.getDeclaredFields())
                .map(Field::getName)
                .filter(n -> n.toLowerCase().contains("password") || n.toLowerCase().contains("pwd"))
                .toArray(String[]::new);

        assertThat(offenders)
                .as("A credential field on UserDTO would be mapped automatically by MapStruct and "
                        + "returned by every user endpoint. The absence of the field is the "
                        + "protection; there is no @Mapping(ignore) to rely on.")
                .isEmpty();
    }

    /**
     * Verifies the nested role set is delegated to {@code RoleMapper} and arrives fully mapped.
     *
     * <p>{@code uses = RoleMapper.class} is the only composition in the project. Roles drive every
     * authorization decision, so a set that arrived empty would present an admin as having no
     * roles — and, unlike a null, an empty set does not throw anywhere. It renders as a user with
     * no permissions and looks like an authorization bug rather than a mapping one.
     */
    @Test
    void theNestedRoleSetIsMappedThroughRoleMapper() {
        UserDTO dto = mapper.toDTO(aUser(Set.of(role(RoleName.ROLE_ADMIN), role(RoleName.ROLE_USER))));

        assertThat(dto.getRoles())
                .as("an empty set here reads as a user with no permissions")
                .hasSize(2)
                .extracting(RoleDTO::getName)
                .containsExactlyInAnyOrder(RoleNameDTO.ROLE_ADMIN, RoleNameDTO.ROLE_USER);
    }

    /**
     * Verifies a user with no roles maps to an empty set rather than null.
     *
     * <p>Reachable in practice: a user is built before its roles are resolved during registration.
     * A null set would NPE anything iterating {@code dto.getRoles()} — including the JSON
     * serialiser's collection handling and any client-side {@code roles.map(...)}.
     */
    @Test
    void aUserWithNoRolesMapsToAnEmptySetNotNull() {
        UserDTO dto = mapper.toDTO(aUser(Set.of()));

        assertThat(dto.getRoles()).isNotNull().isEmpty();
    }

    /** Verifies a null entity maps to a null DTO rather than throwing. */
    @Test
    void aNullUserMapsToNull() {
        assertThat(mapper.toDTO(null)).isNull();
    }

    /**
     * Verifies a null role collection on the entity does not throw.
     *
     * <p>{@code User.roles} has a {@code @Builder.Default}, so this needs an explicit null to reach
     * — but the entity is also constructed by JPA and by the all-args constructor, neither of which
     * applies the default.
     */
    @Test
    void aNullRoleCollectionDoesNotThrow() {
        User user = aUser(Set.of());
        user.setRoles(null);

        assertThat(mapper.toDTO(user).getRoles()).isNull();
    }
}
