# photo-app-feign-lib

Declarative HTTP clients for service-to-service calls, plus the auth interceptor and error
decoder that make them behave correctly.

**Not a service — a library.** No port, no `main`, no container.

## Contents

| Class | What |
|---|---|
| `UserFeignClient` | → users-service. **The only client on a hot path** (`isActive`) |
| `AccountFeignClient` | → accounts-service. Declared, not currently exercised |
| `AlbumFeignClient` | → albums-service. Declared, not currently exercised |
| `PhotoFeignClient` | → photos-service. Declared, not currently exercised |
| `FeignAuthInterceptor` | Forwards the caller's JWT downstream |
| `CustomFeignErrorDecoder` | Maps downstream HTTP errors to application exceptions |
| `FeignConfiguration` | Wiring |

## The one live call

```
accounts-service ──► UserFeignClient#isActive ──► GET /users/{id}/active ──► users-service
```

Everything else is declared for future use. Knowing this matters when reading a trace: if a
request fans out to more than two services, something has changed.

## Why the interceptor exists

`GET /users/{id}/active` is `@PreAuthorize("hasRole('ADMIN')")`. Without
`FeignAuthInterceptor` copying the caller's `Authorization` header onto the outbound request,
the downstream call arrives anonymous and is rejected — and, before the `AccessDeniedException`
handler was added, that surfaced as an opaque `500` rather than a `403`.

So the JWT is propagated deliberately: the downstream authorization check sees the *original*
principal, not the calling service.

## Resilience

Circuit breaking and retry are **not** configured here — they live in the calling service's
config-repo properties, keyed by instance name (`photo-app-users-service-isActive`,
`photo-app-albums-service-countByAccountId`). Note
`spring.cloud.openfeign.circuitbreaker.enabled=false`: the breaker is applied at the service
layer rather than wrapping the Feign client itself.

## Consumed by

All five business services declare it. Only accounts-service currently exercises it.

## Future

This is one of the two libraries considered a genuine candidate for extraction into a reusable
toolkit (with [`photo-app-security-lib`](../photo-app-security-lib)) — unlike
`photo-app-commons` and `photo-app-entity-model-lib`, whose coupling is an accepted decision.
Captured in the AWS backlog of
[`../../../docs/plans/dockerization-plan.md`](../../../docs/plans/dockerization-plan.md).

## See also

- [../../services/photo-app-accounts-service](../../services/photo-app-accounts-service) — the caller, with breaker settings
- [../../../docs/ARCHITECTURE.md](../../../docs/ARCHITECTURE.md)
