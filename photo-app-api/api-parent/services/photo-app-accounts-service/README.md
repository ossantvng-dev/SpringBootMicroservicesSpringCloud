# photo-app-accounts-service

Account management. A user owns accounts; an account owns albums.

**Port:** 8082 (not published — reached through the gateway)

## Swagger UI

- Through the gateway: http://localhost:8080/swagger-ui.html → select **accounts-service**
- Direct (native runs only): http://localhost:8082/swagger-ui.html

## Access

Every endpoint requires a JWT with `USER` or `ADMIN`. There are no public paths.

## The one inter-service call in the system

Before creating an account, this service confirms the owning user is active:

```
accounts-service ──► UserFeignClient#isActive ──► GET /users/{id}/active ──► users-service
```

Wrapped in Resilience4j, instance `photo-app-users-service-isActive`:

| Setting | Value |
|---|---|
| Sliding window | 10 calls, COUNT_BASED |
| Minimum calls | 5 |
| Failure rate threshold | 50% |
| Wait in open state | 10s |
| Retry | 3 attempts, exponential backoff from 2s, multiplier 2 |

`FeignAuthInterceptor` forwards the caller's JWT so the downstream `@PreAuthorize("hasRole('ADMIN')")`
on `/users/{id}/active` sees the original principal rather than an anonymous request.

There is also a circuit breaker registered for
`photo-app-albums-service-countByAccountId`.

This is the path to look at first when a trace shows unexplained latency — see the latency item
in [`../../../docs/plans/backlog.txt`](../../../docs/plans/backlog.txt).

## A mapping bug worth knowing about

`AccountDTO.accountTypeDTO` maps from `Account.accountType`. The names differ, and under the old
ModelMapper configuration the field was silently `null` in API responses while the database held
`PREMIUM`. The MapStruct migration surfaced it, and `AccountMapper` now maps it explicitly.

`unmappedTargetPolicy = ReportingPolicy.ERROR` means a recurrence is a compile error, not a
silent null.

## Depends on

| On | Why |
|---|---|
| MySQL | `accounts` |
| **users-service** | `isActive` check — the only synchronous dependency |
| Config Server, Discovery, RabbitMQ, Zipkin | Standard for every service |
| `photo-app-commons`, `photo-app-entity-model-lib`, `photo-app-security-lib`, `photo-app-feign-lib`, `photo-app-tracing-lib` | Shared libraries |

## Environment variables

| Variable | Required | Purpose |
|---|---|---|
| `CONFIG_SERVER_ADMIN_USER` / `CONFIG_SERVER_ADMIN_PASSWORD` | yes | Config Server auth |
| `CONFIG_SERVER_URL` | container | `http://photo-app-config-server:8888` |
| `SPRING_DATASOURCE_URL` | container | Points at `photo-app-mysql` |
| `EUREKA_CLIENT_SERVICEURL_DEFAULTZONE`, `EUREKA_INSTANCE_HOSTNAME` | container | Registration |
| `SPRING_RABBITMQ_HOST`, `MANAGEMENT_ZIPKIN_TRACING_ENDPOINT` | container | Bus and tracing |

## See also

- [../../../docs/ARCHITECTURE.md](../../../docs/ARCHITECTURE.md) — service-to-service calls
- [../../libraries/photo-app-feign-lib](../../libraries/photo-app-feign-lib) — the Feign clients
