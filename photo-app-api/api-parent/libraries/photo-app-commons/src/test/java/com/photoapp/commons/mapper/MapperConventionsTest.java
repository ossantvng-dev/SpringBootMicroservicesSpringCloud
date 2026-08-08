package com.photoapp.commons.mapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the compile-time protection that Step 5's ModelMapper → MapStruct migration installed.
 *
 * <p>{@code unmappedTargetPolicy = ReportingPolicy.ERROR} is what turns "a target field nobody
 * mapped" into a build failure. It is the direct fix for the {@code accountType} /
 * {@code accountTypeDTO} defect, where ModelMapper matched on name, found nothing, and shipped a
 * null through a 200 response for as long as the endpoint existed.
 *
 * <p>The protection has a weakness this class exists to cover: <strong>it is opt-in per mapper, and
 * removing it is silent.</strong> MapStruct's default is {@code WARN}, so deleting the attribute
 * does not fail anything — it downgrades a build error to a line of build output nobody reads, and
 * the next unmapped field ships as null. Nothing else in the project would notice.
 *
 * <p><strong>Why this reads source files rather than using reflection or a classpath scan.</strong>
 * Two earlier attempts failed, both silently, and both are worth recording because the next person
 * will try them in the same order:
 *
 * <ol>
 *   <li>{@code ClassPathScanningCandidateComponentProvider} — its default
 *       {@code isCandidateComponent} rejects interfaces, so it finds no mappers at all.</li>
 *   <li>Reflection, and then Spring's ASM {@code MetadataReader} — {@code org.mapstruct.Mapper} is
 *       declared {@code @Retention(RetentionPolicy.CLASS)}. It never reaches the JVM's reflection
 *       API, and Spring's {@code AnnotationMetadata} does not surface
 *       {@code RuntimeInvisibleAnnotations} either: every mapper interface reports an empty
 *       annotation list. Verified directly against the compiled classes.</li>
 * </ol>
 *
 * <p>Both produced an empty collection, which would have left every parameterised test here
 * passing on zero arguments — this initiative's signature failure, reproduced inside the guard
 * meant to prevent it. {@link #theScanFindsEveryMapperInThisLibrary} is what caught it, and is the
 * reason that test exists.
 *
 * <p>The declaration under guard is a source-level one, so reading the source is not a workaround;
 * it is the only place the fact actually lives.
 */
class MapperConventionsTest {

    private static final Path MAPPER_SOURCES =
            Path.of("src", "main", "java", "com", "photoapp", "commons", "mapper");

    /** One mapper interface, as its source text. */
    record MapperSource(String name, String source) {

        boolean declares(String attribute, String value) {
            // Tolerant of spacing, since formatting is not what is under test.
            return source.replaceAll("\\s+", "").contains(attribute + "=" + value);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<MapperSource> commonsMappers() throws IOException {
        try (Stream<Path> files = Files.list(MAPPER_SOURCES)) {
            return files
                    .filter(p -> p.getFileName().toString().endsWith("Mapper.java"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .map(p -> {
                        try {
                            return new MapperSource(
                                    p.getFileName().toString().replace(".java", ""),
                                    Files.readString(p));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .filter(m -> m.source().contains("@Mapper("))
                    .toList()
                    .stream();
        }
    }

    static Stream<MapperSource> entityMappers() throws IOException {
        return commonsMappers().filter(m -> !m.name().equals("PagedResponseMapper"));
    }

    /**
     * Verifies the scan finds the mappers it is supposed to, so the parameterised tests below
     * cannot pass by scanning nothing.
     *
     * <p>Not defensive padding — it has already earned its place twice, on the two failed
     * approaches described in the class Javadoc. It also fails if the working directory is not the
     * module root, which is the way a source-reading test most plausibly breaks.
     */
    @Test
    @DisplayName("the scan actually finds all six mappers — not an empty list")
    void theScanFindsEveryMapperInThisLibrary() throws IOException {
        assertThat(commonsMappers().map(MapperSource::name).toList())
                .as("an empty or short list here means every other test in this class is passing "
                        + "vacuously; check that %s resolves from the module root",
                        MAPPER_SOURCES)
                .containsExactly(
                        "AccountMapper",
                        "AlbumMapper",
                        "PagedResponseMapper",
                        "PhotoMapper",
                        "RoleMapper",
                        "UserMapper");
    }

    /**
     * THE STEP 5 GUARD. Verifies every entity mapper still declares
     * {@code unmappedTargetPolicy = ReportingPolicy.ERROR}.
     *
     * <p>The compile-time policy only protects a mapper that still has it. This test is what fails
     * when someone deletes the attribute to make a stubborn build pass — the exact change that
     * would reopen the original defect for that mapper, silently and permanently.
     */
    @ParameterizedTest(name = "{0} declares unmappedTargetPolicy = ReportingPolicy.ERROR")
    @MethodSource("entityMappers")
    void everyEntityMapperFailsTheBuildOnAnUnmappedTarget(MapperSource mapper) {
        assertThat(mapper.declares("unmappedTargetPolicy", "ReportingPolicy.ERROR"))
                .as("%s dropped unmappedTargetPolicy = ReportingPolicy.ERROR. That attribute is "
                        + "the fix for the accountType/accountTypeDTO defect: MapStruct's default "
                        + "is WARN, so without it a target field nobody mapped is silently null "
                        + "and the build still passes.", mapper.name())
                .isTrue();
    }

    /** Verifies every mapper is a Spring bean, so services inject them all the same way. */
    @ParameterizedTest(name = "{0} declares componentModel = spring")
    @MethodSource("commonsMappers")
    void everyMapperIsASpringBean(MapperSource mapper) {
        assertThat(mapper.declares("componentModel", "\"spring\""))
                .as("%s is not a Spring bean, so it cannot be injected like the others",
                        mapper.name())
                .isTrue();
    }

    /**
     * Documents why {@code PagedResponseMapper} is the one mapper without the policy.
     *
     * <p>It declares only {@code default} methods, so MapStruct generates nothing and there is no
     * unmapped target for a policy to govern. Asserting that here, rather than leaving it as a bare
     * exclusion above, means that if it ever gains an abstract method — a real generated mapping —
     * this fails and forces the policy decision to be made deliberately.
     */
    @Test
    @DisplayName("PagedResponseMapper is policy-exempt only because it generates nothing")
    void theOnlyPolicyExemptMapperHasNoGeneratedMappings() {
        List<String> abstractMethods = Arrays.stream(PagedResponseMapper.class.getDeclaredMethods())
                .filter(m -> !m.isDefault() && !m.isSynthetic())
                .map(java.lang.reflect.Method::getName)
                .toList();

        assertThat(abstractMethods)
                .as("PagedResponseMapper gained an abstract method, so MapStruct now generates a "
                        + "real mapping for it — give it unmappedTargetPolicy = ERROR like the rest "
                        + "and remove it from the exclusion in entityMappers()")
                .isEmpty();
    }
}
