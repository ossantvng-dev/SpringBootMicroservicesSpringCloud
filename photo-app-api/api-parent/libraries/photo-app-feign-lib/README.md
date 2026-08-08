# photo-app-feign-lib

Declarative HTTP clients for service-to-service calls, plus the auth interceptor and error
decoder that make them behave correctly.

**Not a service — a library.** No port, no `main`, no container.

## Contents

| Class | What |
|---|---|
| `UserFeignClient` | → users-service. 3 methods, 2 with live callers |
| `AccountFeignClient` | → accounts-service. 5 methods, 2 with live callers |
| `AlbumFeignClient` | → albums-service. 6 methods, 3 with live callers |
| `PhotoFeignClient` | → photos-service. 6 methods, 2 with live callers |
| `FeignAuthInterceptor` | Forwards the caller's JWT downstream |
| `CustomFeignErrorDecoder` | Maps downstream HTTP errors to application exceptions |
| `DownstreamFailurePredicate` | Decides what counts as a failure *of the downstream* |
| `FeignFallbacks` | Shared fallback translation — preserves a downstream 4xx |
| `FeignConfiguration` | Per-client wiring (decoder + interceptor) |
| `FeignTransportAutoConfiguration` | Turns off HttpClient 5's own retries |

## The live calls

Reconciled against the code on 2026-08-07 (testing-plan.md Phase 4). Nine of the twenty
methods have a caller:

```
accounts ──► UserFeignClient#isActive                        ──► users
accounts ──► AlbumFeignClient#countByAccountId               ──► albums
albums   ──► AccountFeignClient#findById                     ──► accounts
albums   ──► PhotoFeignClient#countByAlbumIds                ──► photos
photos   ──► AlbumFeignClient#findById                       ──► albums
photos   ──► AccountFeignClient#findById                     ──► accounts
users    ──► AccountFeignClient#deleteByUserId               ──► accounts   ┐ delete
users    ──► AlbumFeignClient#deleteByAccountIds             ──► albums     │ cascade
users    ──► PhotoFeignClient#deleteByAlbumIds               ──► photos     ┘
auth     ──► UserFeignClient#findByUsernameAndActiveUser     ──► users
```

The remaining eleven — all three `findAll`, all three `activateOrDeactivate`, all three
`deleteById`, `PhotoFeignClient#findById` and `UserFeignClient#findById` — are declared and
uncalled. That is not harmless: it is how the PATCH transport defect stayed invisible.

## Transport

`feign-hc5` (Apache HttpClient 5), declared by each of the five services. Optional here so this
library can compile `FeignTransportAutoConfiguration` without imposing a transport on consumers.

Two reasons it is not Feign's default `Client$Default`:

- `java.net.HttpURLConnection` **cannot send PATCH** — it rejects the verb from a fixed whitelist
  before opening a socket, which made all three `activateOrDeactivate` methods unreachable.
- It silently retries an idempotent request once when the connection breaks, doubling
  Resilience4j's attempts with nothing reporting the real number.

Selection is by classpath presence alone (`@ConditionalOnClass(ApacheHttp5Client)`), so nothing
fails loudly if the dependency is dropped — `PatchVerbTest` is the only thing that would notice.

HttpClient 5 has a retry strategy of its own (`maxRetries=1`, plus 429/503 responses), so
`FeignTransportAutoConfiguration` disables it. Two retry layers that do not know about each other
is the problem, not the count either one picks.

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

Twelve of the twenty methods carry `@CircuitBreaker` + `@Retry` + a fallback. What the two
classes in this package decide is easy to conflate, and both are needed:

- `DownstreamFailurePredicate` — whether the circuit **opens**, and whether a call is **retried**.
  A downstream 4xx is the callee working correctly and saying no, so it counts as neither.
- `FeignFallbacks.translate` — what the **caller is told**. A downstream 4xx passes through with
  its own status; anything else becomes 503.

Both were added on 2026-08-05 after five logins with mistyped usernames opened the users-service
circuit and the next valid login got a 503. `FeignResilienceMatrixTest` guards them across all
twelve methods.

## Consumed by

All five business services declare it, and all five exercise it — see the call map above.

## Future

This is one of the two libraries considered a genuine candidate for extraction into a reusable
toolkit (with [`photo-app-security-lib`](../photo-app-security-lib)) — unlike
`photo-app-commons` and `photo-app-entity-model-lib`, whose coupling is an accepted decision.
Captured in the AWS backlog of
[`../../../docs/plans/dockerization-plan.md`](../../../docs/plans/dockerization-plan.md).

## See also

- [../../services/photo-app-accounts-service](../../services/photo-app-accounts-service) — the caller, with breaker settings
- [../../../docs/ARCHITECTURE.md](../../../docs/ARCHITECTURE.md)
