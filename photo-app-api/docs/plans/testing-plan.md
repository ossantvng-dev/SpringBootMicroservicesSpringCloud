# Testing Plan — comprehensive unit and integration testing

**Status:** Phase 1 complete (2026-08-05). Phases 2 and 3 complete (2026-08-06). Phase 4 complete
(2026-08-07). Phases 5-8 outstanding. **527 tests across eight modules.**
**Companion to:** [backlog.txt](backlog.txt) — the HIGH-priority testing initiative dated
2026-08-02, which this plan expands. Decisions already recorded there (use `@SpringBootTest` /
`@WebMvcTest` with MockMvc; Testcontainers with MySQL over H2) are treated as settled and are
not re-litigated here.

**Why this exists:** the Step 8 authorization defect — a `ROLE_USER` token on an `ADMIN`
endpoint returned **500 instead of 403**, because `@PreAuthorize`'s `AuthorizationDeniedException`
was swallowed by `GlobalExceptionHandler`'s `@ExceptionHandler(Exception.class)` catch-all. It
was **pre-existing**, not introduced by any step, and survived undetected only because every
manual test token happened to carry `ADMIN`. The goal is to test the whole *class* of defect.

This plan finds **three more live instances of that same class**, listed in §2.4.

---

# PART 1 — Inventory

## 1.0 Current test baseline (measured, 2026-08-04)

Worth stating plainly before planning anything: **effective coverage is zero.**

| Module | Test file | Status | What it asserts |
|---|---|---|---|
| commons | `AppTest` | ACTIVE | `assertTrue(true)` |
| feign-lib | `AppTest` | ACTIVE | `assertTrue(true)` |
| config-server | `…ApplicationTests` | ACTIVE | `contextLoads` |
| discovery | `…ApplicationTests` | ACTIVE | `contextLoads` |
| discovery-cluster | `…ApplicationTests` | ACTIVE | `contextLoads` |
| gateway | `…ApplicationTests` | **`@Disabled`** | — |
| users, accounts, albums, photos, authorization | `…ApplicationTests` | **`@Disabled`** | — |

11 test files, 4 that run and assert anything meaningful, **0 lines of behaviour covered**.

Two findings that matter for Phase 1:

- **Every reactor build in this project has run `-DskipTests`.** The four "active" tests had
  never actually been executed in this session's history. They were run for this analysis and
  do pass (`BUILD SUCCESS`, 4/4).
- **`PhotoAppConfigurationServerApplicationTests` passes only because of ambient machine
  state.** It needs `CONFIG_SERVER_ADMIN_USER`, `CONFIG_SERVER_ADMIN_PASSWORD`,
  `KEYSTORE_PASSWORD`, `GIT_USERNAME` and `GIT_TOKEN` as OS environment variables. On a clean
  CI runner it fails. The discovery test passes but floods the log with Eureka
  `Connection refused` attempts against `localhost:8761`.

## 1.1 Controllers and endpoints

**37 endpoints across 5 controllers.** The gateway has **no controllers** — it is routing plus
the aggregating Swagger UI, so it gets integration-level tests only (§2.6), never `@WebMvcTest`.

### photo-app-users-service — `UserController` @ `/users` (10 endpoints)

| Method | Path | `@PreAuthorize` |
|---|---|---|
| POST | `/users` | *(none — public registration)* |
| GET | `/users/username/{username}` | *(none — public)* |
| PUT | `/users/{id}` | `hasRole('ADMIN') or hasRole('USER')` |
| GET | `/users/{id}` | `hasRole('ADMIN') or hasRole('USER')` |
| GET | `/users/email/{email}` | `hasRole('ADMIN')` |
| GET | `/users/{id}/active` | `hasRole('ADMIN')` |
| GET | `/users` | `hasRole('ADMIN')` |
| PATCH | `/users/{id}/activate` | `hasRole('ADMIN')` |
| PATCH | `/users/{id}/roles` | `hasRole('ADMIN')` |
| DELETE | `/users/{id}` | `hasRole('ADMIN')` |

> `GET /users/{id}/active` is the target of `UserFeignClient#isActive` — the one live
> inter-service call. It is `ADMIN`-only, which is why `FeignAuthInterceptor` must forward the
> caller's token. A test that breaks the interceptor must fail *here*.

### photo-app-accounts-service — `AccountController` @ `/accounts` (8 endpoints)

| Method | Path | `@PreAuthorize` |
|---|---|---|
| POST | `/accounts` | `hasRole('ADMIN')` |
| PATCH | `/accounts/{id}/name` | `hasRole('ADMIN') or hasRole('USER')` |
| PATCH | `/accounts/{id}/type` | `hasRole('ADMIN')` |
| GET | `/accounts/{id}` | `hasRole('ADMIN') or hasRole('USER')` |
| GET | `/accounts` | `hasRole('ADMIN') or hasRole('USER')` |
| PATCH | `/accounts/{id}/activate` | `hasRole('ADMIN') or hasRole('USER')` |
| DELETE | `/accounts/{id}` | `hasRole('ADMIN') or hasRole('USER')` |
| DELETE | `/accounts/byUser/{userId}` | `hasRole('ADMIN')` |

### photo-app-albums-service — `AlbumController` @ `/albums` (8 endpoints)

| Method | Path | `@PreAuthorize` |
|---|---|---|
| POST | `/albums` | `hasRole('ADMIN') or hasRole('USER')` |
| GET | `/albums/{id}` | `hasRole('ADMIN') or hasRole('USER')` |
| GET | `/albums` | `hasRole('ADMIN') or hasRole('USER')` |
| GET | `/albums/countByAccountId` | `hasRole('ADMIN') or hasRole('USER')` |
| PUT | `/albums/{id}` | `hasRole('ADMIN') or hasRole('USER')` |
| PATCH | `/albums/{id}/activate` | `hasRole('ADMIN') or hasRole('USER')` |
| DELETE | `/albums/{id}` | `hasRole('ADMIN') or hasRole('USER')` |
| DELETE | `/albums/byAccountIds` | `hasRole('ADMIN')` |

### photo-app-photos-service — `PhotoController` @ `/photos` (8 endpoints)

| Method | Path | `@PreAuthorize` |
|---|---|---|
| POST | `/photos` | `hasRole('ADMIN') or hasRole('USER')` |
| GET | `/photos/{id}` | `hasRole('ADMIN') or hasRole('USER')` |
| GET | `/photos` | `hasRole('ADMIN') or hasRole('USER')` |
| GET | `/photos/countByAlbumIds` | `hasRole('ADMIN') or hasRole('USER')` |
| PUT | `/photos/{id}` | `hasRole('ADMIN') or hasRole('USER')` |
| PATCH | `/photos/{id}/activate` | `hasRole('ADMIN') or hasRole('USER')` |
| DELETE | `/photos/{id}` | `hasRole('ADMIN') or hasRole('USER')` |
| DELETE | `/photos/byAlbumIds` | `hasRole('ADMIN')` |

### photo-app-authorization-service — `AuthorizationController` @ `/auth` (3 endpoints)

| Method | Path | `@PreAuthorize` |
|---|---|---|
| POST | `/auth/login` | *(none — public by design)* |
| POST | `/auth/refresh` | *(none)* |
| POST | `/auth/revoke` | *(none)* |

> `POST /auth/revoke` being unauthenticated means **anyone holding a refresh token string can
> revoke it**, and nothing binds the caller to the token's owner. Whether that is intended is an
> open question — see §3.

### Authorization matrix size

**32 endpoints carry `@PreAuthorize`**; 5 are public (3 auth + 2 users). At three cases each
(no token → 401, wrong role → 403, right role → through), that is **96 authorization
assertions**, plus 5 public-access assertions. This is the Step 8 bug class and the highest-value
block in the plan.

## 1.2 `GlobalExceptionHandler` — 11 handlers, one shared advice

*(7 at inventory time; #8-11 added by Phase 2 on 2026-08-06.)*

Single `@RestControllerAdvice` in `photo-app-commons`, consumed by all five services — **not** by
the gateway, which component-scans only `com.photoapp.gateway` and `com.photoapp.security`.
**Order is load-bearing**, so tests must pin it.

| # | Handler | Catches | Status | Log line |
|---|---|---|---|---|
| 1 | `applicationExceptionHandler` | `ApplicationException` | **from the exception** | `APPLICATION_EXCEPTION` @WARN |
| 2 | `validationExceptionHandler` | `MethodArgumentNotValidException` | 400 | `VALIDATION_ERROR` @WARN |
| 3 | `constraintViolationHandler` | `ConstraintViolationException` | 400 | `CONSTRAINT_VIOLATION` @WARN |
| 4 | `dataAccessExceptionHandler` | `DataAccessException` | 500 | `DATABASE_ERROR` @ERROR |
| 5 | `optimisticLockExceptionHandler` | `OptimisticLockException` | 409 | `OPTIMISTIC_LOCK_CONFLICT` @WARN |
| 6 | `accessDeniedHandler` | `AccessDeniedException` | **403** | `ACCESS_DENIED` @WARN |
| 7 | `genericExceptionHandler` | `Exception` | 500 | `UNHANDLED_EXCEPTION` @ERROR |
| 8 | `typeMismatchHandler` | `MethodArgumentTypeMismatchException` | 400 | `TYPE_MISMATCH` @WARN |
| 9 | `noResourceFoundHandler` | `NoResourceFoundException` | 404 | `RESOURCE_NOT_FOUND` @WARN |
| 10 | `messageNotReadableHandler` | `HttpMessageNotReadableException` | 400 | `MALFORMED_REQUEST_BODY` @WARN |
| 11 | `methodNotSupportedHandler` | `HttpRequestMethodNotSupportedException` | 405 | `METHOD_NOT_ALLOWED` @WARN |

All produce `ApiErrorDTO` = `{httpStatus, error, message, path, timeStamp}`.

Test notes:
- **#6 must be asserted to sit above #7.** That ordering *is* the Step 8 fix. A regression test
  that only checks "403 comes back" is weak; assert the `ACCESS_DENIED` marker too, since a
  future refactor could return 403 by a different route.
- **#1 is the interesting one**: `ApplicationException` carries its own `HttpStatus`, so it is a
  status *multiplexer* — the service layer throws 403/404/409/400 through it. Every service's
  business rules funnel through here.
- `timeStamp` is `LocalDateTime.now()` at build time → assert its presence and type, never an
  exact value.

## 1.3 Feign clients — 4 interfaces, 20 methods, 12 protected, 12 fallbacks

| Client | Method | `@Retry` | `@CircuitBreaker` | Fallback |
|---|---|---|---|---|
| `UserFeignClient` | `isActive` | ✅ | ✅ | `isActiveFallback` |
| | `findByUsernameAndActiveUser` | ✅ | ✅ | `findByUsernameAndActiveUserFallback` |
| | `findById` | ✅ | ✅ | `findByIdFallback` |
| `AccountFeignClient` | `findById` | ✅ | ✅ | `findByIdFallback` |
| | `findAll` | ✅ | ✅ | `findAllFallback` |
| | `deleteByUserId` | ✅ | ✅ | `deleteByUserIdFallback` |
| | `activateOrDeactivate` | ❌ | ❌ | — |
| | `deleteById` | ❌ | ❌ | — |
| `AlbumFeignClient` | `findById` | ✅ | ✅ | `findByIdFallback` |
| | `findAll` | ✅ | ✅ | `findAllFallback` |
| | `deleteByAccountIds` | ✅ | ✅ | `deleteByAccountIdsFallback` |
| | `countByAccountId` | ✅ | ✅ | `countByAccountIdFallback` |
| | `activateOrDeactivate` | ❌ | ❌ | — |
| | `deleteById` | ❌ | ❌ | — |
| `PhotoFeignClient` | `deleteByAlbumIds` | ✅ | ✅ | `deleteByAlbumIdsFallback` |
| | `countByAlbumIds` | ✅ | ✅ | `countByAlbumIdsFallback` |
| | `findById` | ❌ | ❌ | — |
| | `findAll` | ❌ | ❌ | — |
| | `activateOrDeactivate` | ❌ | ❌ | — |
| | `deleteById` | ❌ | ❌ | — |

**8 of the 20 methods have no resilience protection at all**, and the gaps are not obviously
principled — `AccountFeignClient#findAll` is protected while `PhotoFeignClient#findAll` is not.
Per client: Account 2 unprotected of 5, Album 2 of 6, **Photo 4 of 6**, User 0 of 3.
`PhotoFeignClient` is the outlier — two-thirds of it is unguarded. Flagged in §4.

**Every fallback throws** `ApplicationException(…, SERVICE_UNAVAILABLE)` — none returns a
degraded value. So the circuit breaker delivers *fail-fast with a uniform 503*, not graceful
degradation. That is a legitimate design choice but worth naming, because it means:
- fallback tests assert a **thrown exception**, not a returned default;
- an open circuit and a downstream 503 are indistinguishable to the caller.

**Supporting classes:** `CustomFeignErrorDecoder` maps 401→401, 403→403, 404→404, 503→503,
everything else→500, each wrapped in `ApplicationException`. `FeignAuthInterceptor` copies the
inbound `Authorization` header onto the outbound request, and **silently no-ops when there is no
request context** (`RequestContextHolder` returns null) — which is exactly what happens on an
async or scheduled call. Worth a dedicated test.

### Live call paths

| Caller → callee | Via |
|---|---|
| accounts → users | `UserFeignClient#isActive` (on `createAccount`) |
| accounts → albums | `AlbumFeignClient#countByAccountId` (on `deleteById`) |
| authorization → users | `UserFeignClient#findByUsernameAndActiveUser` (on login) |

## 1.4 Resilience4j configuration

Shared `default` config, identical in all five dev profiles:

```properties
circuitbreaker.configs.default.slidingWindowType=COUNT_BASED
circuitbreaker.configs.default.slidingWindowSize=10
circuitbreaker.configs.default.minimumNumberOfCalls=5
circuitbreaker.configs.default.failureRateThreshold=50
circuitbreaker.configs.default.waitDurationInOpenState=10s
retry.configs.default.maxAttempts=3
retry.configs.default.waitDuration=2s
retry.configs.default.enableExponentialBackoff=true
retry.configs.default.exponentialBackoffMultiplier=2
```

Instances explicitly declared per service (all `baseConfig=default`):

| Service | Declared instances |
|---|---|
| users | accounts-findAll, accounts-findById, accounts-deleteByUserId, albums-findAll, albums-deleteByAccountIds, photos-deleteByAlbumIds |
| accounts | users-isActive, albums-countByAccountId |
| albums | accounts-findById, photos-countByAlbumIds |
| photos | albums-findById, accounts-findById |
| authorization | users-findByUsernameAndActiveUser, users-findById |

**Testing implication — these numbers make real circuit-breaker tests slow.**
`minimumNumberOfCalls=5` plus `waitDurationInOpenState=10s` plus retry backoff (2s → 4s, 3
attempts) means one closed→open→half-open cycle takes **tens of seconds** if driven through real
config. Do **not** drive transitions through the production values. Instead register a
test-only instance with a tiny window and short wait, or drive `CircuitBreaker` directly from
its registry. Recorded as a decision in §3.

## 1.5 MapStruct mappers — 8 interfaces

All `componentModel = "spring"`, all `unmappedTargetPolicy = ReportingPolicy.ERROR`.

**In `photo-app-commons` (shared, entity → DTO):**

| Mapper | Methods | Explicit `@Mapping` |
|---|---|---|
| `AccountMapper` | `toDTO(Account)`, `toEntity(CreateAccountInputDTO)`, `toDTO(AccountType)`, `toEntity(AccountTypeDTO)` | `accountType→accountTypeDTO`, `accountTypeDTO→accountType`, `activeAccount` ignored |
| `UserMapper` | `toDTO(User)` | — |
| `AlbumMapper` | `toDTO(Album)` | — |
| `PhotoMapper` | `toDTO(Photo)` | — |
| `RoleMapper` | `toDTO(Role)`, `toDTOs(Set<Role>)`, `toEntity(RoleDTO)`, `toDTO(RoleName)`, `toEntity(RoleNameDTO)` | — |

**Service-local (input DTO → entity; live in the owning service to avoid a cycle):**

| Mapper | Method | Ignored targets |
|---|---|---|
| `UserInputMapper` | `toEntity(CreateUserInputDTO)` | `roles`, `passwordHash`, `activeUser` |
| `AlbumInputMapper` | `toEntity(CreateAlbumInputDTO)` | `activeAlbum` |
| `PhotoInputMapper` | `toEntity(CreatePhotoInputDTO)` | `activePhoto` |

**`AccountMapper.toDTO` is the single highest-value mapper test in the codebase.** The
`accountType → accountTypeDTO` line is the fix for the bug where the API returned `null` while
the database held `PREMIUM`. `ReportingPolicy.ERROR` guards *unmapped* fields at compile time; it
does **not** guard a *wrongly* mapped field. A runtime test is the only thing that catches an
inverted or mis-sourced `@Mapping`.

The `ignore = true` targets deserve tests too: they assert a field is deliberately *not* carried
across, which is a security property for `passwordHash` and `roles`.

## 1.6 Repositories and query logic

Five repositories. `deleteBy*` / `existsBy*` / `countBy*` are derived queries — Spring Data
generates them, so the risk is in the **derivation**, not the code.

| Repository | Method | Kind | Recommendation |
|---|---|---|---|
| `UserRepository` | `findByEmail` | derived | **mock** |
| | `findByUsernameAndActiveUser` | derived, 2-arg | **Testcontainers** — compound predicate |
| | `existsByEmail`, `existsByEmailAndUsername` | derived | **Testcontainers** — uniqueness semantics |
| | `findAll(Specification, Pageable)` | dynamic | **Testcontainers** |
| `RoleRepository` | `findByName(RoleName)` | derived, enum param | **Testcontainers** — enum ↔ column mapping |
| `AccountRepository` | `existsByUserId` | derived | mock |
| | `deleteByUserId` | derived, **bulk delete** | **Testcontainers** — FK/cascade behaviour |
| | `findAll(Specification, Pageable)` | dynamic | **Testcontainers** |
| `AlbumRepository` | `deleteByAccountIdIn(List)` | bulk delete, `IN` | **Testcontainers** |
| | `countByAccountIdAndActiveAlbumTrue` | derived, boolean-literal | **Testcontainers** |
| | `countByAccountId` | derived | mock |
| | `findIdsByAccountIdIn` | **`@Query` JPQL projection** | **Testcontainers** |
| | `findAll(Specification, Pageable)` | dynamic | **Testcontainers** |
| `PhotoRepository` | `deleteByAlbumIdIn(List)` | bulk delete, `IN` | **Testcontainers** |
| | `countByAlbumIdIn(List)` | derived, `IN` | **Testcontainers** |
| | `findAll(Specification, Pageable)` | dynamic | **Testcontainers** |

**Rule of thumb applied above:** mock when the method is a trivial single-column lookup whose
only risk is "was it called"; use a real database when the outcome depends on SQL generation —
`IN` clauses, bulk deletes, `LIKE`/`lower()` collation, boolean literals, enum binding, JPQL
projections, or pagination + sorting.

### Specifications — the real query logic

Four `*Specification.fromFilter(FilterDTO)` classes building JPA Criteria predicates. These are
**pure functions returning a `Specification`**, so they are unit-testable in isolation — but
their *correctness* is only observable against a database.

| Specification | `predicates.add` calls | Filter fields |
|---|---|---|
| `UserSpecification` | 11 | `firstName`, `lastName`, `username`, `email` — all `like(lower(col), %v%)`; `active`; **`createdAt` + `updatedAt` three-branch ranges** |
| `AccountSpecification` | 10 | `accountName`, `accountTypeDTO`, `activeAccount`, `userId`; **`createdAt` + `updatedAt` three-branch ranges** |
| `PhotoSpecification` | 7 | `fileName`, `albumId`, `activePhoto`; **`createdAt` + `updatedAt` three-branch ranges** |
| `AlbumSpecification` | 4 | `accountIds` — **comma-split string → `List<Long>` → `IN`**; `title`, `description` `like(lower())`; `activeAlbum`. **No date ranges** |

**Three of the four carry the same three-branch date-range logic** (both bounds / start-only /
end-only) on two fields each — 6 independently reachable branches per specification, 18 in total.
That is the densest branch logic in the codebase and the strongest argument for table-driven
tests rather than hand-written cases. `AlbumSpecification` is the odd one out: no date ranges,
but it owns the only string-parsing predicate, which is also §2.4 defect #2.

Every specification ends `cb.and(predicates.toArray(new Predicate[0]))` — with an **empty**
filter that is `and()` of nothing, which JPA treats as always-true. Assert that an empty filter
returns everything rather than nothing.

---

# PART 2 — Testing strategy

## 2.1 Dependencies — what exists, what to add

**Present today** (and inconsistent across modules — see the table in §1.0):

| Module group | Current test deps |
|---|---|
| users, accounts, photos, authorization | `spring-boot-starter-test`, `spring-security-test` |
| albums | `spring-boot-starter-actuator-test`, `-data-jpa-test`, `-validation-test`, `-webmvc-test` (sliced starters) |
| gateway | `spring-boot-starter-webmvc-test` |
| config-server | `spring-boot-starter-actuator-test` |
| discovery, discovery-cluster | `spring-boot-starter-test` |
| commons, feign-lib, security-lib | `junit-jupiter` **only** |
| entity-model-lib, tracing-lib, database | **none** |

**Not present anywhere:** Testcontainers, WireMock, Mockito (only transitively via
`spring-boot-starter-test`), Awaitility, JaCoCo, PIT.

**Proposed additions**, managed centrally in `api-parent/pom.xml` `<dependencyManagement>` so
versions stay aligned:

| Artifact | Scope | Why |
|---|---|---|
| `org.testcontainers:testcontainers-bom` | import | Version alignment |
| `org.testcontainers:mysql` | test | Real MySQL 8.4 for persistence tests |
| `org.testcontainers:junit-jupiter` | test | `@Testcontainers` / `@Container` lifecycle |
| `org.springframework.boot:spring-boot-testcontainers` | test | `@ServiceConnection` — wires datasource automatically |
| `org.wiremock:wiremock-standalone` | test | Stub downstream HTTP for Feign tests |
| `org.awaitility:awaitility` | test | Circuit-breaker state transitions without `Thread.sleep` |
| `org.springframework.security:spring-security-test` | test | **Add to albums**, which lacks it |
| `net.javacrumbs.json-unit:json-unit-assertj` | test | Assert `ApiErrorDTO` / `Page<T>` shape while ignoring `timeStamp` |

**First normalize what is already there**: give all five services the same test dependency set.
The albums module using sliced `*-test` starters while its four siblings use
`spring-boot-starter-test` is drift, not design, and it will produce confusing "works in four
services, fails in the fifth" results.

**Testcontainers over H2 — confirmed**, and this inventory strengthens the case beyond the
backlog's general reasoning. Concretely, H2 would mask: `like(lower(col))` collation behaviour,
`deleteBy…In` bulk-delete semantics against real FK constraints, MySQL `TIMESTAMP` precision in
the `createdAt`/`updatedAt` range predicates, and enum-to-column binding in
`RoleRepository.findByName`. Every one of those is a place a real bug could hide.

**Reuse the existing Liquibase changelogs** to build the test schema rather than
`ddl-auto=create`. The `database/` module is the single source of schema truth; a test schema
generated by Hibernate would be testing a schema that does not exist in production. This also
gives migration coverage for free.

## 2.2 Test layers and when to use each

| Layer | Annotation | Context | Use for |
|---|---|---|---|
| **Plain unit** | none (JUnit + Mockito) | none | Specifications, mappers, utils, `CustomFeignErrorDecoder`, `CurrentUserService` |
| **Web slice** | `@WebMvcTest(XController.class)` | controller + advice + security filter chain; service mocked | Authorization matrix, validation, status/shape, exception handling |
| **Persistence slice** | `@DataJpaTest` + Testcontainers | JPA only, real MySQL | Repositories, specifications against real SQL |
| **Full context** | `@SpringBootTest` | everything | Wiring smoke tests, Feign + resilience with WireMock |

**Default to `@WebMvcTest`.** With 37 endpoints and 96 authorization assertions, full-context
startup per test class is the difference between a suite that runs on every commit and one that
gets skipped. Reserve `@SpringBootTest` for what genuinely needs the whole graph.

**One critical caveat for `@WebMvcTest`:** the security chain lives in `photo-app-security-lib`
and is `@ConditionalOnWebApplication(SERVLET)`, but consumers pick it up by
`@ComponentScan("com.photoapp.security")` — the library has **no auto-configuration**. A bare
`@WebMvcTest` will therefore **not** load `SecurityConfiguration`, and every authorization test
would pass vacuously against a default-permissive chain. This must be solved in Phase 1 with a
shared `@Import`-based test configuration, and Phase 1 must include a **deliberately failing
control test** (a `USER` token on an `ADMIN` endpoint expecting 403) proving the real chain is
active. Without that control, the entire Phase 3 suite is worthless.

## 2.3 Structure and naming

Mirror `src/main/java` package-for-package:

```
src/test/java/com/photoapp/<service>/
├── controller/       XControllerWebMvcTest.java
├── service/          XServiceImplTest.java
├── repository/       XRepositoryIT.java
│   └── specification/ XSpecificationTest.java
├── mapper/           XMapperTest.java
└── support/          shared fixtures, builders, base classes
src/test/resources/
└── application-test.properties
```

Naming, tied to Maven phase:

| Suffix | Meaning | Plugin | Phase |
|---|---|---|---|
| `*Test` | Fast, no container, no Docker | surefire | `test` |
| `*IT` | Needs Testcontainers or full context | **failsafe** | `verify` |

Splitting on the suffix means `mvn test` stays fast and Docker-free, while `mvn verify` runs the
heavy suite. That matters here: contributors without Docker running can still get a useful
signal, and CI can parallelize the two.

**Cross-cutting fixtures** (JWT minting for a given role, `CustomUserPrincipal` builders, entity
builders) are needed by all five services. Where they should live is an open question — §3.

## 2.4 Code that is hard to test as-is

**To be treated as proposed refactors, decided explicitly — not applied silently.**

### 🔴 Three live instances of the Step 8 bug class

Each is an unguarded parse of client-supplied input reaching the catch-all as a **500 where a
400 is correct** — the same shape as the original defect.

1. **`PaginationUtil.mapToPageable`** — `Integer.parseInt(params.get("page"))` with no guard.
   `GET /users?page=abc` → `NumberFormatException` → handler #7 → **500**. Affects the `findAll`
   of all four listing services.
2. **`AlbumSpecification.fromFilter`** — `accountIds` is split on `,` and mapped with
   `Long::valueOf`, unguarded. `GET /albums?accountIds=abc` → **500**.
3. **`AccountServiceImpl.findAll`** — `Long.valueOf(currentUserId)` where `currentUserId` comes
   from `CurrentUserService.getCurrentUserId()`, which **returns `null`** when the principal is
   not a `CustomUserPrincipal`. Null → `NumberFormatException` → **500**.

These are worth confirming with a test *before* fixing, exactly as the Step 8 defect was.

### 🟠 Missing seams

4. **No `Clock` anywhere.** `TokenHandlerServiceImpl` uses `System.currentTimeMillis()` in four
   places; `JwtTokenProvider` and `JwtClaimsParser` use `new Date()`. Testing token expiry
   therefore requires `Thread.sleep` or reflection. Injecting `java.time.Clock` makes expiry
   tests instant and deterministic. **Highest-value refactor in this list.**
5. **`TokenHandlerServiceImpl` stores refresh tokens in an in-memory `ConcurrentHashMap`.** Not
   shared across instances, not durable across restarts. A testability problem *and* a
   correctness problem the moment there is more than one replica.
6. **`CurrentUserService` reads `SecurityContextHolder` statically.** Workable via
   `@WithMockUser` / manual context population, but every service test that touches ownership
   scoping must set it up. A shared `@WithMockPhotoAppUser` annotation in Phase 1 would pay for
   itself.
7. **Static imports of `fromFilter`, `mapToFilter`, `mapToPageable`, `normalizeInputDTO`** in all
   four service impls. Static calls cannot be stubbed, so service-layer tests necessarily
   exercise the real implementations. Acceptable — they are pure functions — but it means a
   service unit test failing may actually be a *utility* bug. Test the utilities directly first
   so the blame is unambiguous.

### 🟡 Design observations surfaced by the inventory

8. **`UserFeignClient.findByUsernameAndActiveUser` returns `User` — the JPA entity** — across a
   service boundary, while every sibling method returns a DTO. It leaks `passwordHash` and the
   `roles` association over the wire between services.
9. **8 of 20 Feign methods have no resilience annotations** (§1.3), with no evident principle
   behind which do and which do not. `PhotoFeignClient` is the outlier at 4 unprotected of 6.
10. **`BaseEntity` sets `createdAt`/`updatedAt` at field initialization**, not via `@PrePersist`.
    Instances get a timestamp when *constructed*, not when persisted.
11. **`POST /auth/revoke` is unauthenticated** and does not bind the caller to the token owner.

## 2.5 What the gateway gets

No controllers, so no `@WebMvcTest`. Meaningful gateway tests are integration-level:

- Route resolution: `/users/**` → `PHOTO-APP-USERS-SERVICE` (predicates and `lb://` targets).
- The `SetPath` rewrite for `/api-docs/{service}` → `/v3/api-docs`.
- Security headers on the way through: `X-Frame-Options: DENY`, CSP `script-src 'self'`.
- `/swagger-ui/**` and `/v3/api-docs/**` reachable anonymously — this would have caught the
  `spring.cloud.gateway.server.webmvc.function.enabled` 415 regression.
- X-Forwarded propagation producing `servers[0].url = http://localhost:8080` — this would have
  caught the OpenAPI server-URL bug.

Both of those were found by hand this month. They are exactly what a gateway suite is for.

---

# PART 3 — Phased checklist

Structured like `dockerization-plan.md`: numbered phases, each with a stop-and-review gate.
Ordering is by **defect-finding value per unit of effort**, not by architectural layer — the
Step 8 bug class comes first because it is proven to exist and proven to hide.

## Phase 1 — Test infrastructure ✅ DONE 2026-08-05

Nothing below works until this is right.

- ✅ Normalize test dependencies across all five services; add `spring-security-test` to albums
- ✅ Add Testcontainers BOM + `mysql` + `junit-jupiter` + `spring-boot-testcontainers` to `api-parent`
- ✅ Add WireMock, Awaitility, json-unit
- ✅ Configure **failsafe** for `*IT`, keep surefire for `*Test`
- ✅ **Stop building with `-DskipTests`** — currently universal, so nothing is ever verified
- ✅ Shared test config that loads the real `SecurityConfiguration` under `@WebMvcTest`
- ✅ **Control test proving the real security chain is active** (USER on ADMIN endpoint → 403).
  Gate: if this passes with a permissive chain, Phase 3 is invalid.
- ✅ Test JWT minting helper + `@WithMockPhotoAppUser`
- ✅ Testcontainers MySQL base class, schema built from `database/` Liquibase changelogs
- ✅ Decide where shared fixtures live (§4, Q1)
- ✅ Fix or remove the `@Disabled` `contextLoads` tests, and the config-server test's hidden
  dependency on ambient machine env vars

**Exit:** `mvn verify` runs green with a Testcontainers MySQL, and the control test proves
security is genuinely enforced.

## Phase 2 — Exception handling ✅ DONE 2026-08-06 *(one item deferred)*

> Phase 1 surfaced a large new target for this phase: MethodArgumentTypeMismatchException,
> NoResourceFoundException and un-convertible query parameters ALL fall through to the 500
> catch-all today - measured 8/8 on the running stack. See the 2026-08-05 entry in
> backlog.txt. Roughly 20 {id} endpoints are affected, plus every mistyped URL.

The Step 8 bug lived here. One shared advice, so one suite covers all five services.

- ✅ All handlers: exact status + full `ApiErrorDTO` shape (ignoring `timeStamp` value)
- ✅ **`AccessDeniedException` resolves to #6, not the #7 catch-all** — the Step 8 regression test
- ✅ `ApplicationException` propagates its own status across 400/403/404/409/503
- ✅ Assert the log markers (`ACCESS_DENIED` @WARN, `UNHANDLED_EXCEPTION` @ERROR)
- ✅ Add and test handlers for MethodArgumentTypeMismatchException (400),
  NoResourceFoundException (404), HttpMessageNotReadableException (400) and
  HttpRequestMethodNotSupportedException (405) - all previously 500
- ⬜ Regression-test the three §2.4 defects fixed on 2026-08-05 — **deferred**. `PaginationUtil`
  is a commons class and belongs in this module's suite; `AlbumSpecification` and
  `AccountServiceImpl.findAll` are service classes and their tests must live in the albums and
  accounts modules, which have no suites yet. Folded into Phases 3 and 7.

**Exit:** every handler branch covered; handler ordering pinned by test. ✅

### What was built

54 tests in `libraries/photo-app-commons/src/test`, plain JUnit + Mockito + AssertJ. Commons
cannot use `photo-app-test-support` (decision 2), so the module took `spring-boot-starter-test`
directly and builds what it needs locally.

| File | What it proves |
|---|---|
| `GlobalExceptionHandlerTest` | Per handler: status, full `ApiErrorDTO` shape, log marker, log **level**, and whether a stack trace was attached |
| `GlobalExceptionHandlerResolutionTest` | Which handler Spring *picks* — via the real `ExceptionHandlerMethodResolver`, plus a guard that fails if an `@ExceptionHandler` is added without a test |
| `GlobalExceptionHandlerWebMvcTest` | Real requests through a real DispatcherServlet, so the framework decides which exception a malformed request produces |
| `support/LogCapture` | Logback `ListAppender` helper — half of what the advice does is invisible in the response body |

**Three layers, because one is not enough.** Calling `accessDeniedHandler` directly would have
passed throughout the Step 8 defect — that handler always worked, it was simply never reached.
Resolution has to be asserted separately from behaviour. And asserting resolution still assumes
`GET /users/abc` produces a `MethodArgumentTypeMismatchException` in the first place, which is
exactly the assumption that was wrong; hence the end-to-end layer.

### Four handlers added

`typeMismatchHandler` (400), `noResourceFoundHandler` (404), `messageNotReadableHandler` (400),
`methodNotSupportedHandler` (405). All log at **WARN** with their own marker, not at ERROR with
`UNHANDLED_EXCEPTION` — a mistyped id in a URL is a client error, not a server fault, and
logging it as one buried genuine faults in noise.

Verified with curl against the running stack before and after:

| Request | Before | After |
|---|---|---|
| `GET /users/abc` | 500 `UNHANDLED_EXCEPTION` @ERROR | 400 `TYPE_MISMATCH` @WARN |
| `GET /users/1/does-not-exist` | 500 `UNHANDLED_EXCEPTION` @ERROR | 404 `RESOURCE_NOT_FOUND` @WARN |
| `PATCH /users/1/activate?activate=maybe` | 500 `UNHANDLED_EXCEPTION` @ERROR | 400 `TYPE_MISMATCH` @WARN |
| `POST /users` with `{"broken` | 500 `UNHANDLED_EXCEPTION` @ERROR | 400 `MALFORMED_REQUEST_BODY` @WARN |
| `DELETE /auth/login` | 500 `UNHANDLED_EXCEPTION` @ERROR | 405 `METHOD_NOT_ALLOWED` @WARN |

### Coverage

`mvn verify -pl libraries/photo-app-commons` — `GlobalExceptionHandler` at **293/293
instructions, 72/72 lines, 18/18 methods, 2/2 branches, 0 missed complexity**.

Two branches only existed because two of them were written for this phase, and JaCoCo earned its
place twice: it caught `lambda$constraintViolationHandler$1` (the `"; "` join) sitting uncovered
because the test payload had a single constrained field, and the completeness guard in the
resolution test caught `validationExceptionHandler` missing from the resolution table. Both were
real gaps in a suite that was otherwise passing.

## Phase 3 — Authorization matrix ✅ DONE 2026-08-06 *(one item moved to Phase 7)*

Table-driven via `@ParameterizedTest`, not hand-written methods. **238 tests**, one suite per
controller plus a path-rule suite for the gateway.

- ✅ Per endpoint: no token → 401, wrong role → 403, correct role → through
- ✅ Both roles where `ADMIN or USER` is allowed
- ✅ Public endpoints reachable anonymously (`POST /users`, `GET /users/username/{u}`, all 3 `/auth/*`)
- ⬜ Ownership scoping: non-admin restricted to own data — **moved to Phase 7**, see below
- ✅ Expired token → 401; malformed token → 401; wrong-signature token → 401
- ✅ Method-level `@PreAuthorize` narrower than the path rule (e.g. `GET /users` is ADMIN-only)
- ✅ Unknown role (`ROLE_GUEST`) → 403 — the path-rule layer, added beyond the original scope

**Exit:** every `@PreAuthorize` endpoint covered at all three levels. ✅

### Inventory, reconciled against the codebase

The count of 32 from Phase 1's analysis still holds exactly. Nothing added or removed since;
Phase 2's four new exception handlers introduced no endpoints, as expected.

| Controller | Protected | ADMIN-only | ADMIN or USER | Public |
|---|---|---|---|---|
| `UserController` | 8 | 6 | 2 | 2 |
| `AccountController` | 8 | 3 | 5 | 0 |
| `AlbumController` | 8 | 1 | 7 | 0 |
| `PhotoController` | 8 | 1 | 7 | 0 |
| `AuthorizationController` | 0 | — | — | 3 |
| API gateway | 0 | — | — | — |
| **Total** | **32** | **11** | **21** | **5** |

The gateway has no controllers at all: it contributes zero protected endpoints and its entire
authorization surface is the path rules it applies before proxying. `GatewayPathAuthorizationTest`
covers that layer and includes a guard that fails if a controller is ever added there.

### What was built

| Suite | Tests |
|---|---|
| `UserControllerAuthorizationTest` | 55 |
| `AccountControllerAuthorizationTest` | 50 |
| `AlbumControllerAuthorizationTest` | 50 |
| `PhotoControllerAuthorizationTest` | 50 |
| `AuthorizationControllerAuthorizationTest` | 11 |
| `GatewayPathAuthorizationTest` | 22 |

160 of these are authorization assertions on the 32 protected endpoints — well past the 96 the
plan estimated, because the matrix grew two states: an unknown-role case (denied by the path
rule rather than method security) and a both-roles case, the account shape that hid the Step 8
defect for months.

Shared fixtures added to `photo-app-test-support`: `ProtectedEndpoint` (one row of the matrix)
and `ControllerEndpoints` (reflection over a controller). Each suite uses the latter to assert
its table names **exactly** the controller's `@PreAuthorize` methods, and that no endpoint is
unprotected by accident — the same completeness guard that caught a missing handler in Phase 2.

### The Boot 4 finding that would have made this vacuous

`spring-boot-security-test` was missing from the classpath. In Boot 4 the security
auto-configuration moved out of `spring-boot-autoconfigure`, and the `@WebMvcTest` slice in
`spring-boot-webmvc-test` no longer lists any of it — **that artifact** is what contributes
`ServletWebSecurityAutoConfiguration` (which supplies the `HttpSecurity` bean
`SecurityConfiguration#securityFilterChain` takes as a parameter) and
`SecurityMockMvcAutoConfiguration` (which puts the chain in front of MockMvc).

Without it a `@WebMvcTest` has **no security chain whatsoever**. Here it failed loudly, because
this project defines its own chain and the missing bean is a hard error — but only for that
reason. A slice relying on Boot's default chain would have come up completely unsecured and
every assertion in this phase would have passed against nothing. Phase 1's control test guards
the *wrong-chain* case; this was the *no-chain* case, one layer beneath it.

Added to `photo-app-test-support` so all six suites inherit it.

### Coverage

`mvn verify` across the six modules. All **37** controller handler methods at **100%
instructions** — every endpoint in the system is now exercised by an authorized request.

Branch coverage on those methods is **0/0**, and that is structural rather than a gap worth
closing: `@PreAuthorize` does not compile to a branch in the controller. It is an annotation
read at runtime by `AuthorizationManagerBeforeMethodInterceptor`, which evaluates the SpEL
expression in a proxy outside the class. **JaCoCo therefore cannot evidence authorization
coverage from a controller report at all** — there is nothing in that bytecode to cover. The
evidence for this phase is the matrix itself, not the coverage number.

The security code that *is* instrumentable lives in `photo-app-security-lib` — `JwtFilter`'s
header/expiry/parse-failure branches and `SecurityConfiguration`'s rule chain. These suites
execute it on every request, but JaCoCo reports only a module's own `target/classes` (the
scoping note from Phase 2), so none of that execution is attributed anywhere. A
`photo-app-security-lib` suite is where measurable security-branch coverage would come from;
it does not exist yet and is not in any phase. Worth adding to Phase 7.

### Defect found

**HIGH — an expired token locks a client out of `/auth`.** All three `/auth` endpoints return
401 when the caller sends an expired token, despite `/auth/**` being `permitAll`. `JwtFilter`
handles `ExpiredJwtException` by calling `sendError(401)` and returning *without continuing the
chain*, so the permit rule is never consulted; every other invalid-token shape falls through and
proceeds, which is why a malformed token does not block login but an expired one does.

`/auth/refresh` is the damaging case — its own contract says it must work when the access token
has expired, and the filter defeats that whenever the client also sends the stale token, which
is what every HTTP client interceptor does by default.

**Confirmed against the live stack on 2026-08-07.** Same refresh token, same second, only the
header differing: `POST /auth/refresh` with an expired `Authorization` header → **401**; with no
header → **200**. The 401 ran first and left the token unconsumed, so the 200 that followed
proves the credential was valid all along. All three `/auth` endpoints behave identically, each
paired against its own no-header control. The rejection happens at the **gateway** — the
authorization service never received the refused requests. Pinned as a characterization test
rather than fixed; full write-up in `backlog.txt`.

### Ownership scoping — moved to Phase 7

`canAccessResource` is called 20 times across the four `*ServiceImpl` classes and **zero** times
in any controller. It is a service-layer concern, and every suite in this phase mocks the
service layer by design, so a controller slice test cannot reach it: mocking the service is
exactly what removes the code under test. It belongs with the service-impl suites in Phase 7.

Listing it under Phase 3 was an error in the original plan, not a change of scope.

## Phase 4 — Feign and resilience ✅ DONE (2026-08-07)

**223 tests in `photo-app-feign-lib`.** 100% JaCoCo on every class in the module — including
branches, which Phase 3 could not measure at all.

- ✅ WireMock stubs per client; success paths for all 20 methods
- ✅ `CustomFeignErrorDecoder` — all five branches (401/403/404/503/other)
- ✅ All 12 fallbacks **throw** `ApplicationException` with 503
- ✅ `FeignAuthInterceptor` forwards the header; **and no-ops without a request context**
- ✅ Circuit breaker closed → open → half-open → closed — against **test-only config** (§3, Q3)
- ✅ Retry attempt count and backoff
- ✅ Live paths end-to-end: accounts→users `isActive`, accounts→albums `countByAccountId`,
  authorization→users `findByUsernameAndActiveUser`
- ✅ Regression guards for both 2026-08-05 fixes, plus the inverse property they put at risk

**Exit:** every annotated method's fallback asserted; decoder fully covered. ✅

### Inventory, reconciled against the codebase

**4 clients, 20 remote methods, 12 fallbacks** — exactly the numbers this phase was scoped
against. `FeignClientInventoryTest` re-derives all three by reflection on every build, so the
count cannot drift away from the suites silently.

| Client | Methods | With `@CircuitBreaker` + `@Retry` + fallback | Unannotated |
|---|---|---|---|
| `UserFeignClient` | 3 | 3 | 0 |
| `AccountFeignClient` | 5 | 3 | 2 |
| `AlbumFeignClient` | 6 | 4 | 2 |
| `PhotoFeignClient` | 6 | 2 | 4 |
| **Total** | **20** | **12** | **8** |

Two things the reconciliation turned up that the plan had not recorded:

**Nine of the twenty methods have no call site anywhere in the reactor.** All three
`activateOrDeactivate`, all three `deleteById`, `PhotoFeignClient#findById`,
`PhotoFeignClient#findAll`, and `UserFeignClient#findById`. That last one is the call the
2026-08-05 refresh fix removed; its orphaned circuit-breaker instance was deleted from the config
repo on 2026-08-07 after the running authorization service reported `bufferedCalls: 0` for it
while actively serving logins.

> **Corrected 2026-08-07.** This first read "eleven of the twenty", counting all three `findAll`
> methods as uncalled. Two of them are called. `UserServiceImpl#deleteById` invokes
> `AccountFeignClient#findAll` and then `AlbumFeignClient#findAll` to walk a user's accounts and
> albums before cascading the delete — and it writes them as
> `accountFeignClient` newline `.findAll(...)`, so a single-line grep for
> `accountFeignClient.findAll(` matches nothing and the methods look dead. Only
> `PhotoFeignClient#findAll` is genuinely uncalled. Caught while acting on the wrong conclusion:
> the cleanup that was about to delete their config found the callers first. The lesson is
> mechanical — **search for Feign call sites with a multiline pattern**, because fluent chains put
> the receiver and the method on different lines.

**The eight unannotated methods are inconsistent in a way that looks accidental.** `findById` is
protected on the account and album clients but not the photo client; `findAll` is protected on
two clients and not the third. Nothing is broken today because none of the eight is called.
`FeignClientInventoryTest#theUnprotectedMethodsAreKnown` pins the list, so adding annotations to
any of them is a deliberate act that also forces the method into the resilience matrix.

### What was built

| Suite | Tests | What it covers |
|---|---|---|
| `FeignResilienceMatrixTest` | 85 | 7 properties × the 12 protected methods |
| `FeignFallbacksTest` | 39 | all 12 fallbacks × 3 properties, plus `translate` directly |
| `CustomFeignErrorDecoderTest` | 17 | all five branches, method-key propagation, one characterization |
| `DownstreamFailurePredicateTest` | 18 | the predicate in isolation, including cause-chain walking |
| `FeignClientInventoryTest` | 13 | the completeness guards |
| `CircuitBreakerStateTransitionTest` | 11 | the state machine on test-only instances |
| `AlbumFeignClientTest` / `PhotoFeignClientTest` | 7 each | success paths, encoding, deserialisation |
| `AccountFeignClientTest` | 6 | as above, plus the no-fallback asymmetry |
| `RetryBehaviourTest` | 6 | attempt counts, backoff shape, recovery |
| `UserFeignClientTest` | 5 | success paths |
| `FeignAuthInterceptorTest` | 5 | header forwarding, no-context no-op, both end-to-end |
| `PatchVerbTest` | 4 | the PATCH regression guard (was a characterization test) |

The matrix is the centre of the phase: seven properties asserted uniformly across all twelve
protected methods rather than hand-written per client, because twelve × seven is eighty-four
cases and the one written by hand that gets skipped is always the one that breaks.

### The regression guards, and the property they put at risk

Three of the seven matrix properties guard the 2026-08-05 fix (`DownstreamFailurePredicate` +
`FeignFallbacks.translate`): six consecutive downstream 404s leave the circuit **closed**, a 4xx
is **not retried**, and a 4xx **reaches the caller with its own status**. A fourth repeats the
first two for 403, because the predicate keys off `is4xxClientError()` rather than off any one
status.

The other three exist because those four could all be satisfied by a predicate that had become
*too* permissive — one returning `false` for everything would pass every guard above while the
circuit breaker had quietly become decorative. So: a genuine 5xx **does** open the circuit and
**does** produce 503; a transport failure (no status in the cause chain at all — the predicate's
default branch) does the same; and an open circuit **stops contacting the downstream** while
still answering 503 rather than leaking `CallNotPermittedException`.

**Confirmed live on 2026-08-07.** Six logins with an unknown username through the gateway, each
answered 401 — then a valid login immediately after, answered **200**. Before the fix the sixth
would have opened the circuit and the valid login would have been 503. The authorization
service's own actuator afterwards:

```
"photo-app-users-service-findByUsernameAndActiveUser":
    {"bufferedCalls":8,"failedCalls":0,"failureRate":"0.0%","state":"CLOSED"}
```

Eight calls recorded — well past `minimumNumberOfCalls=5` — and zero counted as failures. That
is the fix, measured on the running system rather than inferred from a green suite.

### Coverage — and the contrast with Phase 3

`photo-app-feign-lib`, from `target/site/jacoco/jacoco.xml`:

| Counter | Covered | Missed |
|---|---|---|
| Instruction | 275 | **0** |
| Branch | 21 | **0** |
| Line | 60 | **0** |
| Method | 27 | **0** |
| Class | 10 | **0** |

(Measured after the transport follow-up below, which added `FeignTransportAutoConfiguration` —
the tenth class.)

Unlike Phase 3, the branch number here is real and worth reading. `@PreAuthorize` compiles to an
annotation, not a branch, so JaCoCo reported 0/0 branches on every controller and could not
evidence authorization coverage at all. Feign's resilience layer is ordinary Java: the decoder's
switch is 5 branches, `DownstreamFailurePredicate` 6, `FeignFallbacks` 6, `FeignAuthInterceptor`
4. All 21 are covered, so coverage is actual evidence here rather than a proxy for it.

The 24 methods include all 12 fallbacks — interface `default` methods are the only executable
code on the client interfaces, so "12 of 12 methods covered" on those four classes *is* the
Phase 4 exit criterion, reported by the tool.

### Harness fidelity — two things that would have made this vacuous

Same lesson as Phase 3's `spring-boot-security-test`: a slice that quietly loses the component
under test passes everything.

**1. No `Encoder` at all without `spring-boot-data-commons`.** Every client failed to build with
`No bean found of type interface feign.codec.Encoder`. `FeignClientsConfiguration#feignEncoder`
is `@ConditionalOnMissingClass("org.springframework.data.domain.Pageable")`, and
`spring-data-commons` arrives transitively through `photo-app-commons` — so that bean is *always*
skipped in this reactor. The replacement lives in `FeignClientsConfiguration$SpringDataConfiguration`,
which needs `DataWebProperties` from `spring-boot-data-commons`. The five services inherit it
from `spring-boot-starter-data-jpa`; a library with no JPA has to ask.

**2. `WebEnvironment.MOCK`, not `NONE`.** The encoder and decoder are built from the
`HttpMessageConverters` bean, which the web auto-configuration does not contribute when the
application type is `none`.

Also required and easy to miss: `aspectjweaver`. `@CircuitBreaker` and `@Retry` are Spring AOP
annotations, and without it Spring never registers `AnnotationAwareAspectJAutoProxyCreator` — the
aspects are silently never applied, and every resilience assertion in this phase would pass
against a bare Feign call. The services get it from `spring-boot-starter-data-jpa` →
`spring-aspects`.

And a fourth, added in the follow-up below: `feign-hc5`, so the suite runs the same transport the
five services now ship. Whichever transport production uses, this module must use it — the PATCH
and retry-count assertions are meaningless otherwise, and they would pass either way.

### Aspect ordering, measured

`circuit-breaker-aspect-order=1`, `retry-aspect-order=2`, so the **breaker is outermost and the
retry runs inside it**. Measured, not assumed: one logical call against a downstream returning
500 delivers three requests and records **one** breaker failure, not three. This is why
`minimumNumberOfCalls=5` means five *logical* calls rather than five attempts, and why
`RetryBehaviourTest#aCallThatRecoversOnTheThirdAttemptSucceeds` can assert the breaker recorded
zero failures for a call that failed twice on the wire.

### Defects found

Three. Two were fixed on 2026-08-07 in the follow-up described below; the third is open. All
three are written up in [backlog.txt](backlog.txt).

**MEDIUM — PATCH is unreachable over Feign's default client. FIXED.** All three
`activateOrDeactivate` methods threw `ProtocolException: Invalid HTTP method: PATCH` before a
socket was opened; `java.net.HttpURLConnection` has never allowed the verb. Not a harness
artefact — `feign-hc5` was absent from all five service images, with no okhttp, no custom
`feign.Client` bean, and no `spring.cloud.openfeign.httpclient.*` property anywhere. Invisible
because none of the three has a caller.

**LOW — a transport failure reaches the downstream six times, not three. FIXED.** One logical
call against an unreachable downstream delivered six requests while `maxAttempts=3` and every
metric said three. The amplification landed specifically on the unreachable-downstream case; a
decoded 5xx was not doubled.

**LOW — a non-standard status escapes `CustomFeignErrorDecoder` as `IllegalArgumentException`.
STILL OPEN.** `HttpStatus.valueOf` runs before the switch and rejects unregistered codes, so the
fallback finds no `ApplicationException` in the chain and reports 503. Not reachable from this
system today — every service answers with standard codes — so it is left for a misbehaving proxy
or a future third-party downstream to make relevant.

## Phase 4 follow-up — the transport change (2026-08-07)

`io.github.openfeign:feign-hc5` added to all five business services, and to
`photo-app-feign-lib` as `<optional>true</optional>` so the library compiles the auto-configuration
below without forcing a transport on its consumers. Selection is by classpath presence alone —
`HttpClient5FeignLoadBalancerConfiguration` is `@ConditionalOnClass(ApacheHttp5Client)` — so there
is no bean and no property to inspect, and nothing fails loudly if the dependency is ever dropped.
`PatchVerbTest` is the only thing that would notice.

**The transport change alone did not fix the retry amplification, and the original diagnosis was
wrong about why.** Measured immediately after adding `feign-hc5`, before anything else changed:

| | before (`Client$Default`) | with `feign-hc5` | after disabling transport retries |
|---|---|---|---|
| unreachable downstream, `maxAttempts=3` | 6 | **6** | **3** |
| downstream 500, `maxAttempts=3` | 3 | 3 | 3 |
| downstream 503, method has **no** `@Retry` | 1 | **2** | **1** |

HttpClient 5 has its own `DefaultHttpRequestRetryStrategy` — `maxRetries=1`, and its default
retriable codes are 429 and 503. So the switch *moved* the amplification rather than removing it,
and widened it: the old client only retried a broken connection, HC5 also retries 503 responses.
The middle column's third row is the sharpest statement of the problem — a retry on a method where
retrying was explicitly not asked for.

The fix is `FeignTransportAutoConfiguration` in `photo-app-feign-lib`: an
`HttpClientBuilderCustomizer` bean calling `disableAutomaticRetries()`, registered through
`AutoConfiguration.imports` because none of the five services component-scans `com.photoapp.feign`.
Resilience4j is now the only retry authority — one place to configure, one set of metrics, and
`DownstreamFailurePredicate` actually consulted. The transport's retries were invisible to all
three.

The general lesson is worth more than the numbers: **two retry layers that do not know about each
other is the defect, not the count either one picks.** Both transports had one; only measuring the
downstream's request journal showed it.

Also removed the same day: the orphaned `photo-app-users-service-findById` circuit-breaker and
retry instances in the config repo, whose call the 2026-08-05 refresh fix had deleted.

### The 2026-08-05 refresh assertions — deferred to Phase 7

The "Two fixes from 2026-08-05" section below also asks Phase 4 to assert that refresh succeeds
with no Authorization header, that a deactivated user is refused, that a revoked or unknown
refresh token is refused, and that a reissued token carries **current** roles rather than the
roles frozen at login.

Those are `TokenHandlerServiceImpl` behaviours, not Feign behaviours — they need the token store
and the service, and they sit above the client interface this phase tests. They belong with the
service-impl suites in Phase 7, alongside the ownership-scoping work moved there from Phase 3.
What Phase 4 *does* cover is the mechanism underneath them:
`FeignAuthInterceptorTest#anInboundRequestWithoutATokenForwardsNothing` pins the constraint the
refresh fix was built around — **any Feign call reachable from a public endpoint must target a
public downstream endpoint**, because nothing in this system will invent a credential.

## Phase 5 — Mappers ⬜

Cheap, fast, and one of these bugs already shipped.

- ⬜ All 8 mappers, every method
- ⬜ **`AccountMapper.toDTO` — `accountType → accountTypeDTO`**, the regression test for the
  silent-null bug
- ⬜ `ignore = true` targets are genuinely not carried (`passwordHash`, `roles`, `active*`)
- ⬜ Null inputs and empty collections
- ⬜ `RoleMapper.toDTOs(Set)` collection mapping

**Exit:** every mapper method covered including the ignore assertions.

## Phase 6 — Repository and persistence ⬜

Testcontainers MySQL, schema from Liquibase. `*IT`, run under failsafe.

- ⬜ Base class + schema bootstrap
- ⬜ All four `*Specification.fromFilter` against real data
- ⬜ **Date-range branches — 6 each in `User`, `Account` and `Photo` specifications, 18 total**
- ⬜ **`AlbumSpecification` comma-split `accountIds` → `IN`**, including the malformed input from §2.4
- ⬜ Empty filter returns everything (the `and()`-of-nothing case)
- ⬜ Bulk deletes (`deleteByUserId`, `deleteByAccountIdIn`, `deleteByAlbumIdIn`) and FK behaviour
- ⬜ `findIdsByAccountIdIn` JPQL projection
- ⬜ `RoleRepository.findByName` enum binding
- ⬜ Pagination and sorting: page size, offset, sort direction, out-of-range page
- ⬜ Optimistic locking → `OptimisticLockException` → 409 (ties to Phase 2 #5)

**Exit:** every non-trivial query exercised against real MySQL.

## Phase 7 — Happy-path controllers ⬜

Deliberately last: lowest defect-finding value, and largely covered incidentally by Phase 3's
"correct role → through" leg.

- ⬜ CRUD per controller: create/read/update/delete
- ⬜ List endpoints — assert the **concrete serialised `Page<T>` shape** (there is no
  `PagedResponseDTO`; `Page<T>` JSON is historically unstable across Spring versions)
- ⬜ Validation failures → 400 with field detail. **`@Valid` appears on exactly 8 request bodies**
  (users 3, albums 2, photos 2, accounts 1) — and on **none** of the three `/auth/*` endpoints, so
  `LoginRequestDTO` is not bean-validated at all. Confirm whether that is intended (§4, Q9)
- ⬜ Business rules: album limits by account type, "cannot delete account with albums" → 409,
  "no changes detected" → 400, inactive-account guards
- ⬜ `/auth/login`, `/auth/refresh`, `/auth/revoke` including expiry (needs the Clock seam, §2.4 #4)

**Exit:** every endpoint has at least one happy-path assertion on status and body shape.

## Phase 8 — Gateway ⬜

- ⬜ Route resolution for all 10 routes
- ⬜ `SetPath` rewrite for `/api-docs/{service}`
- ⬜ Security headers present
- ⬜ Swagger paths anonymous (the 415 regression)
- ⬜ X-Forwarded → correct OpenAPI `servers[0].url` (the server-URL regression)

---

# PART 4 — Decisions (resolved 2026-08-05)

All nine open questions are answered. Recorded here in the same form as the resolved-decisions
section of `dockerization-plan.md`, so the reasoning survives the conversation that produced it.

1. **Shared test fixtures — resolved: a `photo-app-test-support` module.** New module under
   `api-parent/libraries/`, consumed by every other module at `<scope>test</scope>` so nothing it
   contains can reach a production image. Accepted over a `test-jar` from `photo-app-commons`
   (which would couple test code to production packaging) and over per-service duplication
   (which would mean five drifting copies of the security fixtures). This is a deliberate
   exception to the library-proliferation non-goal: the alternative is worse.

2. **Library coverage — resolved: direct suites for `commons`, `security-lib` and `feign-lib`;
   indirect only for `entity-model-lib` and `tracing-lib`.** The first three hold the shared
   exception advice, the security chain and the Feign fallbacks — a bug in any one breaks all
   five services at once. The other two are, respectively, annotated data classes and a single
   auto-configuration whose behaviour is only observable in a running context.

3. **Circuit-breaker testing — resolved: test-only Resilience4j instances for behaviour, and
   driving the `CircuitBreaker` object directly from the registry for state-machine assertions.**
   Production values (`minimumNumberOfCalls=5`, `waitDurationInOpenState=10s`, retry backoff
   2s→4s over 3 attempts) make one closed→open→half-open cycle take tens of seconds. Never drive
   transitions through the production config.

4. **The three §2.4 defects — resolved: FIXED 2026-08-05, not deferred to Phase 2.** All three
   confirmed 500 before and 400/401 after, against the running stack. Phase 2 therefore inherits
   them as *regression* tests rather than as discovery work. Details in the commit history and in
   [backlog.txt](backlog.txt). Two of the three turned out to be wider than first written up:
   `PaginationUtil` had the same unguarded parse on `size` and on `direction`, and the
   `accountIds` parse existed twice — the second copy in `AlbumServiceImpl.findAll`, on the
   non-admin path only, so fixing the Specification alone would have left the 500 live for
   exactly the callers the ownership check protects.

5. **Clock refactor — resolved: APPLIED 2026-08-05.** `java.time.Clock` is constructor-injected
   into `TokenHandlerServiceImpl`, `JwtTokenProvider` and `JwtClaimsParser`, with
   `Clock.systemUTC()` registered in `SecurityBeans` under `@ConditionalOnMissingBean`.
   Behaviourally identical in production and verified as such. Note the code used `new Date()`
   and `System.currentTimeMillis()`, not `Instant.now()`, so the substitutions are
   `Date.from(clock.instant())` and `clock.millis()`. Phases 4 and 7 can now assert token issuance
   and expiry with `Clock.fixed(...)` instead of sleeping.

6. **Coverage — resolved: no global percentage.** A global threshold rewards testing getters.
   JaCoCo is added for *visibility* only, with no build-failing gate initially. The real target is
   100% of four specific things: `@PreAuthorize` endpoints (Phase 3), `GlobalExceptionHandler`
   branches (Phase 2), mapper methods (Phase 5), and Feign fallbacks (Phase 4).

7. **Mutation testing — resolved: deferred until Phases 1–5 are complete.** PIT against a
   near-empty suite reports noise; against a real suite it answers a genuine question.

8. **CI `*IT` tier — resolved: not applicable yet.** No CI pipeline exists. Until GitHub Actions
   is set up (see the CI/CD item in the AWS backlog of `dockerization-plan.md`), run the full
   suite locally after each build. The `*Test` / `*IT` split still goes in now, so that when CI
   arrives the two tiers can be scheduled differently without restructuring anything.

9. **Design findings — resolved: logged separately in [backlog.txt](backlog.txt).** They are not
   testing work and this plan stays about testing.

## Two fixes from 2026-08-05 that Phase 4 must cover as regressions

Both were found and fixed the same day this plan's analysis was written, and both are exactly the
class of defect this initiative exists to catch. Neither has a test.

- **`/auth/refresh` required the caller's access token.** It called
  `UserFeignClient#findById` → `GET /users/{id}`, which is `@PreAuthorize`-protected, while
  `FeignAuthInterceptor` forwards only the *inbound* Authorization header — which a refreshing
  client does not have. Redesigned to re-identify from the username now stored on the
  refresh-token record and re-verify through the **public** `GET /users/username/{username}`, the
  same lookup login uses. No caller credential and no service-to-service credential needed.
  *Phase 4 must assert: refresh succeeds with NO Authorization header; a deactivated user is
  refused; a revoked or unknown refresh token is refused; and the reissued token carries CURRENT
  roles rather than the roles frozen at login.*
  → **Moved to Phase 7 (2026-08-07).** These are `TokenHandlerServiceImpl` behaviours, not Feign
  behaviours: they need the token store and the service, and sit above the client interface
  Phase 4 tests. Phase 4 covers the mechanism underneath them — see its "deferred to Phase 7"
  note.

- **A downstream 4xx tripped the circuit breaker and was flattened to 503.** Measured: five
  logins with unknown usernames opened the users-service circuit, after which a *valid* login
  also returned 503 — a denial of service reachable by ordinary user error. Fixed by
  `DownstreamFailurePredicate` (wired as `recordFailurePredicate` and `retryExceptionPredicate`)
  and `FeignFallbacks.translate`, which preserves a downstream 4xx instead of replacing it with a
  blanket 503.
  *Phase 4 must assert: N consecutive downstream 4xx responses do NOT open the circuit; a 4xx is
  not retried; a downstream 4xx reaches the caller with its own status; and a genuine 5xx or
  connection failure still opens the circuit and still produces 503. That last one matters — it is
  the property the predicate could most easily break.*
  → **✅ DONE 2026-08-07.** All four, asserted against every one of the twelve protected methods
  in `FeignResilienceMatrixTest`, plus a fifth for 403 and a sixth proving an open circuit stops
  contacting the downstream. Reproduced live through the gateway as well: six unknown-username
  logins → 401 each, valid login immediately after → 200, breaker `CLOSED` with 8 buffered calls
  and 0 failures.
