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

## Dependencies — deliberately narrow

`spring-boot-starter-data-jpa` was replaced with the three pieces actually needed:
`jakarta.persistence-api`, `spring-tx` and `spring-data-commons`, plus `spring-security-core`.
A library should not drag a full starter — and a connection pool — into every consumer.

Depends on `photo-app-entity-model-lib`, since the mappers convert to and from those entities.
No cycle is possible: the entity library depends on nothing internal.

## Consumed by

users, accounts, albums, photos. **Not** authorization-service, which owns its own DTOs.

## Not generic — on purpose

This library is photo-app-specific by decision, not by accident. Making it reusable is an
explicit **non-goal**; see the non-goal section of
[`../../../docs/plans/dockerization-plan.md`](../../../docs/plans/dockerization-plan.md).

## See also

- [../photo-app-entity-model-lib](../photo-app-entity-model-lib) — the entities these map to
- [../../../docs/ARCHITECTURE.md](../../../docs/ARCHITECTURE.md)
