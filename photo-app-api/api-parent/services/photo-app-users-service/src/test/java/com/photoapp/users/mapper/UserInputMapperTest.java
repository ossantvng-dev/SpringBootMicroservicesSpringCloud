package com.photoapp.users.mapper;

import com.photoapp.commons.dto.role.RoleNameDTO;
import com.photoapp.entity.User;
import com.photoapp.users.dto.CreateUserInputDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link UserInputMapper} — the registration request → entity mapping.
 *
 * <p>Three of the entity's fields are deliberately <em>not</em> mapped, and each ignore is
 * load-bearing in a different way:
 *
 * <ul>
 *   <li>{@code passwordHash} — the caller sets it after hashing. Mapping it would put the
 *       plaintext password from the request straight into the hash column.</li>
 *   <li>{@code roles} — resolved against the role repository, since the request carries names and
 *       the entity needs managed {@code Role} instances.</li>
 *   <li>{@code activeUser} — left to Lombok's {@code @Builder.Default} of true.</li>
 * </ul>
 *
 * <p>The first is the one worth dwelling on. {@code CreateUserInputDTO.password} and
 * {@code User.passwordHash} do not share a name, so MapStruct will not pair them by itself — but
 * the ignore is what stops anyone "fixing" the unmapped-target error by adding the obvious
 * {@code @Mapping(source = "password", target = "passwordHash")}, which compiles, passes
 * {@code ReportingPolicy.ERROR}, and stores every password in clear text.
 */
class UserInputMapperTest {

    private final UserInputMapper mapper = new UserInputMapperImpl();

    private static CreateUserInputDTO anInput() {
        CreateUserInputDTO input = new CreateUserInputDTO();
        input.setFirstName("Ada");
        input.setLastName("Lovelace");
        input.setUsername("ada");
        input.setEmail("ada@photoapp.com");
        input.setPassword("plaintext-secret");
        input.setRoles(Set.of(RoleNameDTO.ROLE_ADMIN));
        return input;
    }

    /** Verifies the four mapped fields carry across. */
    @Test
    void everyMappedFieldReachesTheEntity() {
        User entity = mapper.toEntity(anInput());

        assertThat(entity.getFirstName()).isEqualTo("Ada");
        assertThat(entity.getLastName()).isEqualTo("Lovelace");
        assertThat(entity.getUsername()).isEqualTo("ada");
        assertThat(entity.getEmail()).isEqualTo("ada@photoapp.com");
    }

    /**
     * THE SECURITY PROPERTY. Verifies the plaintext password does not reach {@code passwordHash}.
     *
     * <p>Asserted on the value, not just on nullness: the point is not "the field is empty", it is
     * "the request's plaintext is not in it". A mapper that mapped {@code password} → the field
     * would fail this with the actual secret visible in the failure message, which is the clearest
     * possible signal.
     */
    @Test
    @DisplayName("the plaintext password never reaches passwordHash")
    void thePlaintextPasswordIsNeverMappedOntoTheHash() {
        User entity = mapper.toEntity(anInput());

        assertThat(entity.getPasswordHash())
                .as("the caller hashes and sets this after mapping; a @Mapping(source = "
                        + "\"password\", target = \"passwordHash\") here would store every "
                        + "password in clear text and still compile")
                .isNull();
    }

    /**
     * Verifies roles are not mapped, so the repository-resolved set is what ends up persisted.
     *
     * <p>{@code User.roles} has a {@code @Builder.Default} of an empty set, so the assertion is
     * "empty", not "null" — and the distinction matters: an empty set is what
     * {@code UserServiceImpl} then overwrites with the resolved roles. If MapStruct did map the
     * {@code RoleNameDTO} set, it would produce unmanaged {@code Role} instances with no ids, and
     * persisting them would either fail or insert duplicate role rows.
     */
    @Test
    void rolesAreLeftForTheServiceToResolve() {
        User entity = mapper.toEntity(anInput());

        assertThat(entity.getRoles())
                .as("mapping these would create unmanaged Role instances with no id")
                .isEmpty();
    }

    /**
     * Verifies {@code activeUser} falls back to the builder default of true.
     *
     * <p>A null here would mean every newly registered user fails the {@code activeUser} check on
     * their first login — the account exists, the password is right, and the login is refused.
     */
    @Test
    void activeUserFallsBackToTheBuilderDefault() {
        assertThat(mapper.toEntity(anInput()).getActiveUser()).isTrue();
    }

    /** Verifies a null input maps to null rather than throwing. */
    @Test
    void aNullInputMapsToNull() {
        assertThat(mapper.toEntity(null)).isNull();
    }

    /** Verifies optional fields left null on the request stay null rather than becoming "". */
    @Test
    void unsetOptionalFieldsStayNull() {
        CreateUserInputDTO sparse = new CreateUserInputDTO();
        sparse.setUsername("ada");

        User entity = mapper.toEntity(sparse);

        assertThat(entity.getUsername()).isEqualTo("ada");
        assertThat(entity.getFirstName()).isNull();
        assertThat(entity.getEmail()).isNull();
    }

    /**
     * The Step 5 guard for this module's mapper — the service-side counterpart to
     * {@code MapperConventionsTest} in commons, which cannot see across module boundaries.
     *
     * <p>Reads the source because {@code org.mapstruct.Mapper} is {@code @Retention(CLASS)} and is
     * therefore invisible to both reflection and Spring's ASM metadata reader. See that class for
     * the full account.
     */
    @Test
    @DisplayName("UserInputMapper still declares unmappedTargetPolicy = ERROR")
    void theMapperStillFailsTheBuildOnAnUnmappedTarget() throws Exception {
        String source = Files.readString(Path.of(
                "src", "main", "java", "com", "photoapp", "users", "mapper", "UserInputMapper.java"));

        assertThat(source.replaceAll("\\s+", ""))
                .as("without this attribute a new User field nobody mapped is silently null, "
                        + "which is the accountType/accountTypeDTO defect in a new place")
                .contains("unmappedTargetPolicy=ReportingPolicy.ERROR");
    }
}
