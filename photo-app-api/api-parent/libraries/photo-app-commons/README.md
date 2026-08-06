# photo-app-commons

Shared DTOs, MapStruct mappers, the global exception handler, and small utilities.

**Not a service — a library.** No port, no `main`, no container.

## Contents

| Package | What |
|---|---|
| `dto.user` / `dto.account` / `dto.album` / `dto.photo` / `dto.role` | Response DTOs and enums shared across services |
| `dto.ApiErrorDTO` | The uniform error body every service returns |
| `dto.PaginationInputDTO`, `dto.SortInputDTO` | Paging and sorting inputs |
| `mapper` | MapStruct mappers: `UserMapper`, `AccountMapper`, `AlbumMapper`, `PhotoMapper`, `RoleMapper` |
| `exception.GlobalExceptionHandler` | `@RestControllerAdvice` — shared by every service |
| `exception.ApplicationException` | Base application exception |
| `util` | `FilterBuilderUtil`, `NormalizationUtil`, `PaginationUtil` |

## Mapping: MapStruct, not ModelMapper

All mappers are `componentModel = "spring"` with
**`unmappedTargetPolicy = ReportingPolicy.ERROR`** — an unmapped target field is a *compile
error*, not a silent null at runtime.

That policy is not theoretical. `AccountDTO.accountTypeDTO` had been silently null in API
responses while the database held `PREMIUM`; the migration off ModelMapper surfaced it, and
`AccountMapper` now maps it explicitly.

MapStruct builds entities through Lombok's `@Builder`, which does **not** expose fields
inherited from `BaseEntity`. So `@Mapping(target = "id", ignore = true)` on an inherited field is
rejected as an invalid mapping target — those fields simply are not builder targets.

Mappers for **input** DTOs owned by a single service (`UserInputMapper`, `AlbumInputMapper`,
`PhotoInputMapper`) live in that service, not here — commons cannot reference a service's own
DTOs without creating a cycle.

## GlobalExceptionHandler — one handler, all services

Because this is a single `@RestControllerAdvice` shared by every service, **its ordering has
system-wide blast radius**. The `AccessDeniedException` handler is declared *above* the
catch-all, which is what turns authorization failures into a `403` with an `ACCESS_DENIED` log
line at WARN instead of the `500` they used to produce. Reordering it re-breaks that everywhere
at once.

Eleven handlers. The last four — `MethodArgumentTypeMismatchException` (400),
`NoResourceFoundException` (404), `HttpMessageNotReadableException` (400) and
`HttpRequestMethodNotSupportedException` (405) — were added on 2026-08-06 because every one of
them was reaching the catch-all and coming back as a **500 logged at ERROR with a stack trace**.
`GET /users/abc` was a 500. The status was the visible half of the bug; the other half was that
ordinary client typos were filling the logs with fake server faults.

The suite is in this module's own `src/test`: 54 tests across `GlobalExceptionHandlerTest`
(behaviour), `GlobalExceptionHandlerResolutionTest` (which handler Spring *picks* — the Step 8
regression) and `GlobalExceptionHandlerWebMvcTest` (real requests through a real
DispatcherServlet). Plain JUnit + Mockito: this module cannot depend on `photo-app-test-support`,
which depends on it. **If you add an `@ExceptionHandler`, the resolution test fails until you add
it to the table there** — that guard is intentional.

## Dependencies — deliberately narrow

`spring-boot-starter-data-jpa` was replaced with the three pieces actually needed:
`jakarta.persistence-api`, `spring-tx` and `spring-data-commons`, plus `spring-security-core`.
A library should not drag a full starter — and a connection pool — into every consumer.

Depends on `photo-app-entity-model-lib`, since the mappers convert to and from those entities.
No cycle is possible: the entity library depends on nothing internal.

## Consumed by

All five business services. users, accounts, albums and photos declare it directly;
**authorization-service gets it transitively through `photo-app-feign-lib`** and
component-scans `com.photoapp.commons`, so it picks up `GlobalExceptionHandler` even though it
owns its own DTOs and uses none of the mappers.

That transitive path is easy to miss and is not cosmetic: `DELETE /auth/login` returns an
`ApiErrorDTO` produced by this advice. **Not** the gateway, which scans only
`com.photoapp.gateway` and `com.photoapp.security`.

## Not generic — on purpose

This library is photo-app-specific by decision, not by accident. Making it reusable is an
explicit **non-goal**; see the non-goal section of
[`../../../docs/plans/dockerization-plan.md`](../../../docs/plans/dockerization-plan.md).

## See also

- [../photo-app-entity-model-lib](../photo-app-entity-model-lib) — the entities these map to
- [../../../docs/ARCHITECTURE.md](../../../docs/ARCHITECTURE.md)
