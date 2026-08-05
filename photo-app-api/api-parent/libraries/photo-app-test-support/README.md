# photo-app-test-support

Shared test fixtures for every other module: JWT minting, security principals, entity builders,
a Testcontainers MySQL base, and the configuration that makes a slice test load the **real**
security chain.

**Not a service — a test-scope library.** No port, no `main`, no container. Every consumer
declares it with `<scope>test</scope>`, so nothing here can reach a production image.

## Why it exists as its own module

Deliberate exception to the library-proliferation non-goal in
[`../../../docs/plans/dockerization-plan.md`](../../../docs/plans/dockerization-plan.md).
The alternatives were worse: a `test-jar` from `photo-app-commons` couples test code to
production packaging, and duplicating per service means five drifting copies of the security
fixtures. Recorded as decision 1 in
[`../../../docs/plans/testing-plan.md`](../../../docs/plans/testing-plan.md).

Its own dependencies are **compile-scoped** on purpose: a test-scoped dependency would not reach
consumers transitively, and the point is that a consumer gets the whole test stack from this one
entry.

## What it provides

### `security.TestJwt` — mint tokens

Mints through the **production `JwtTokenProvider`**, not a hand-rolled signer. That is not
fastidiousness: an attempt to forge a token with `openssl` failed against six different guesses
at how the configured secret decodes, because jjwt's Base64 decoder treats the padding inside
`3q2+7w==Q29uU2VjdXJlS2V5...` differently from GNU `base64`. Going through the real provider
means a test token is produced exactly the way the application produces one, so a test can never
pass against a token the application would reject.

| Factory | Yields |
|---|---|
| `adminToken()` / `userToken()` / `adminAndUserToken()` | Normal valid tokens |
| `expiredToken(Duration, …)` / `expiredAdminToken()` | Already-expired, minted with a **rewound `Clock`** — no test sleeps |
| `tokenWithoutSubject(…)` | Signature-valid, no `sub` claim → null user id |
| `tokenWithNonNumericSubject(…)` | Signature-valid, non-numeric `sub` |
| `tokenWithWrongSignature()` | Correct shape, wrong key |
| `malformedToken()` | Not a JWT |
| `bearer(token)` | The full header value |

The last four exist because they are real defect shapes, not hypotheticals: the sub-less and
non-numeric variants are exactly what produced a 500 in `AccountServiceImpl.findAll` before the
2026-08-05 fix.

`expiredToken` is only possible because `Clock` is injectable — see decision 5 in the testing
plan.

### `security.TestPrincipals` — build principals directly

`CustomUserPrincipal` builders, including `withoutUserId()` and `withNonNumericUserId()`.
Needed because `CurrentUserService` reads `SecurityContextHolder` **statically** — there is no
seam to inject a user, so a service-layer unit test has to populate the context for real.

### `security.@WithMockPhotoAppUser` — the annotation to use on MockMvc tests

**Spring's `@WithMockUser` does not work in this codebase.** It installs a
`org.springframework.security.core.userdetails.User` principal, while `CurrentUserService`
does an `instanceof CustomUserPrincipal` check and returns `null` for anything else. Using
`@WithMockUser` would therefore silently produce a null user id and every ownership-scoped test
would exercise the wrong branch — passing for the wrong reason.

```java
@WithMockPhotoAppUser(userId = "2", username = "user1", roles = "ROLE_USER")
```

`userId = ""` models a token with no subject claim (annotation attributes cannot be null).

### `fixtures.TestEntities` — entity builders

Returns Lombok **builders**, not finished entities, so a test overrides only the field it cares
about and everything else stays at a sane default. Whatever a test sets is what the test is about.

Note what these cannot set: `id`, `version`, `createdAt` and `updatedAt` live on `BaseEntity`,
and Lombok's `@Builder` does not expose inherited fields — the same constraint that stops the
MapStruct mappers declaring `@Mapping(target = "id", ignore = true)`.

### `persistence.MySqlContainerSupport` — real MySQL for `*IT`

One MySQL 8.4 container for the whole suite, started in a static initialiser and never stopped
so Testcontainers' Ryuk reaps it at JVM exit. Restarting MySQL per test class would dominate the
runtime.

MySQL rather than H2 is a recorded decision, and the inventory made the reason concrete: H2 would
mask `like(lower(col))` collation, `deleteBy…In` bulk-delete semantics against real foreign keys,
MySQL `TIMESTAMP` precision in the date-range predicates, and enum-to-column binding in
`RoleRepository.findByName`.

Schema comes from the [`database`](../../../database) module's Liquibase changelogs, not
`ddl-auto` — a Hibernate-generated schema would be testing a schema that does not exist in
production, and this way the migrations get exercised for free.

### `web.PhotoAppSecuritySliceConfig` — **load-bearing**

Makes `@WebMvcTest` exercise the real `SecurityConfiguration` and the real
`GlobalExceptionHandler`.

`photo-app-security-lib` has **no auto-configuration**: applications pick the chain up with
`@ComponentScan("com.photoapp.security")`, and a slice test does not component-scan. Without this
config, Boot quietly substitutes its **default** security chain and every authorization assertion
passes against rules that are not the ones shipped — a green, worthless suite.

`SecuritySliceControlTest` in this module's own `src/test` is the guard against exactly that. If
it fails, **fix the configuration, do not weaken the test** — a failure there means the rest of
the suite cannot be trusted.

## Dependencies

| On | Why |
|---|---|
| `spring-boot-starter-test` | JUnit 5, AssertJ, Mockito |
| `spring-boot-starter-webmvc-test` | Boot 4 moved `@WebMvcTest` / `@AutoConfigureMockMvc` here, out of `spring-boot-starter-test` |
| `spring-boot-starter-security` | Supplies Boot's servlet security **auto-configuration**, which defines the `HttpSecurity` bean `SecurityConfiguration` consumes. Without it the slice context fails with *"required a bean of type HttpSecurity"* — the same lesson the gateway taught in Step 1 |
| `spring-security-test` | `@WithSecurityContext` support |
| `photo-app-security-lib` | The real chain, `CustomUserPrincipal`, `JwtTokenProvider` |
| `photo-app-commons` | The real `GlobalExceptionHandler` |
| `photo-app-entity-model-lib` | Entities for the builders |
| Testcontainers (`testcontainers`, `mysql`, `junit-jupiter`) + `spring-boot-testcontainers` | Real MySQL for `*IT` |
| `wiremock-standalone` | Stubbing downstream services for Feign tests |
| `awaitility` | Circuit-breaker transitions without `Thread.sleep` |
| `json-unit-assertj` | Asserting `ApiErrorDTO` / `Page<T>` shape while ignoring volatile fields |

## Consumed by

The five business services, the gateway, and `photo-app-feign-lib`.

**Not** by `photo-app-commons`, `photo-app-security-lib` or `photo-app-entity-model-lib` — this
module depends on all three, and **Maven rejects module cycles regardless of scope**. Their own
suites use plain JUnit instead. This is a real constraint, not an oversight; see decision 2 in
the testing plan.

## See also

- [../../../docs/TESTING.md](../../../docs/TESTING.md) — how testing works across the project
- [../../../docs/plans/testing-plan.md](../../../docs/plans/testing-plan.md) — the phased plan and progress
