package com.photoapp.test.support.persistence;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/*
    Base class for persistence tests (*IT), giving them a real MySQL 8.4.

    MySQL rather than H2 is a recorded decision. This inventory made the reason concrete: H2
    would mask like(lower(col)) collation behaviour, deleteBy...In bulk-delete semantics against
    real foreign keys, MySQL TIMESTAMP precision in the createdAt/updatedAt range predicates,
    and enum-to-column binding in RoleRepository.findByName. Each is somewhere a real bug could
    hide behind a passing test.

    ONE container for the whole suite, not one per class: the container is started in a static
    initialiser and deliberately never stopped, so Testcontainers' Ryuk reaps it when the JVM
    exits. Restarting MySQL per test class would dominate the runtime.

    Schema comes from Liquibase, not ddl-auto. The database/ module is the single source of
    schema truth, so a Hibernate-generated schema would be testing a schema that does not exist
    in production - and this way the migrations themselves get exercised for free.
 */
public abstract class MySqlContainerSupport {

    protected static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
                    .withDatabaseName("photo_app")
                    .withUsername("photo_app_user")
                    .withPassword("password")
                    .withReuse(true);

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);

        // Applications never create schema; Liquibase owns it.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");

        // Point Liquibase at the changelogs owned by the database/ module. The classpath entry
        // resolves because test-support's consumers put those resources on the test classpath;
        // see PERSISTENCE-TESTS note in testing-plan.md Phase 6.
        registry.add("spring.liquibase.enabled", () -> "true");
        registry.add("spring.liquibase.change-log", () -> "classpath:db/changelog/changelog-master.xml");
    }
}
