# Platform Vision — North Star

## Intent

`photo-app` is a working microservices system, but it is also a **laboratory**. The
libraries under `photo-app-api/api-parent/libraries` are not meant to stay
photo-app-specific forever: they are prototypes for a personal, reusable backend
toolkit that can be dropped into any future project — a generic security layer, a
generic approval engine, a generic audit trail, and eventually a generator that
scaffolds a new backend (monolith or microservices) with those pieces already wired
in. Everything currently named `photo-app-*` should be read as "version 0 of a
future `ossantvng-*` toolkit module". This document exists to keep that direction
visible while day-to-day work stays focused on shipping photo-app.

## Planned pieces

| Piece | Status |
|---|---|
| **Backend template generator** — arg-driven scaffolding (`--monolith` / `--microservices`) that emits a wired project skeleton | Not started |
| **Generic security library** — JWT issue/verify, role model, resource-ownership checks, externalized URL rule table | Prototype exists as `photo-app-security-lib` (JWT parser/provider, `CustomUserPrincipal`, `CurrentUserService` with `canAccessResource`, servlet + reactive filters). Rules and property names are still hardcoded to photo-app |
| **Custom authorization server** — built *on top of* Spring Authorization Server (not Keycloak/Okta, not from scratch) | Not started. `photo-app-authorization-service` currently issues its own HMAC JWTs and registers an in-memory PKCE client; it is a stepping stone, not the product |
| **Approval framework** — leveled/multi-stage approvals, reusable across apps | Not started |
| **Alert framework** — token expiry, Feign failures, generic alert routing | Not started |
| **Notifications framework** | Not started |
| **Audit / history framework** — change tracking across entities | Not started. `BaseEntity` in `photo-app-entity-model-lib` has `@CreatedDate` / `@LastModifiedDate` / `@Version`, which is the seed of it |

## Extraction candidates already in the codebase

Small pieces that are already generic and could be lifted with little work:

- `BaseEntity` (id, optimistic locking, auditing timestamps, Hibernate-proxy-safe `equals`)
- `ApiErrorDTO`, `ApplicationException`, `GlobalExceptionHandler`
- `PaginationInputDTO` / `SortInputDTO` / `PaginationUtil` / `FilterBuilderUtil` / `NormalizationUtil`
- `CustomFeignErrorDecoder` and `FeignAuthInterceptor` (header propagation)

See `dockerization-plan.md` §6 for what currently blocks each library from being reused.
