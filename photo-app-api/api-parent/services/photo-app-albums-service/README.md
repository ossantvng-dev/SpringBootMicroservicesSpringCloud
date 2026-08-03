# photo-app-albums-service

Album management, bounded by a configurable per-account-type limit.

**Port:** 8083 (not published — reached through the gateway)

## Swagger UI

- Through the gateway: http://localhost:8080/swagger-ui.html → select **albums-service**
- Direct (native runs only): http://localhost:8083/swagger-ui.html

## Access

Every endpoint requires a JWT with `USER` or `ADMIN`. There are no public paths.

## Album limits

`AlbumLimitsProperties` binds `photoapp.albums.limits.*` from the config repo:

```properties
photoapp.albums.limits.basic=10     # dev
photoapp.albums.limits.basic=20     # prod
```

`getLimitForAccountType` returns the `basic` limit for `AccountTypeDTO.BASIC` and `-1`
(unlimited) for anything else — so `PREMIUM` accounts are currently uncapped.

Because this is `@ConfigurationProperties` served from the Config Server, the limit can be
changed and propagated with `POST /actuator/busrefresh` without a restart.

## Depends on

| On | Why |
|---|---|
| MySQL | `albums` (FK to `accounts`) |
| Config Server, Discovery, RabbitMQ, Zipkin | Standard for every service |
| `photo-app-commons`, `photo-app-entity-model-lib`, `photo-app-security-lib`, `photo-app-feign-lib`, `photo-app-tracing-lib` | Shared libraries |

Calls no other business service on a hot path. accounts-service has a circuit breaker registered
for `photo-app-albums-service-countByAccountId`, so this service is a *callee* on that path.

## Environment variables

| Variable | Required | Purpose |
|---|---|---|
| `CONFIG_SERVER_ADMIN_USER` / `CONFIG_SERVER_ADMIN_PASSWORD` | yes | Config Server auth |
| `CONFIG_SERVER_URL` | container | `http://photo-app-config-server:8888` |
| `SPRING_DATASOURCE_URL` | container | Points at `photo-app-mysql` |
| `EUREKA_CLIENT_SERVICEURL_DEFAULTZONE`, `EUREKA_INSTANCE_HOSTNAME` | container | Registration |
| `SPRING_RABBITMQ_HOST`, `MANAGEMENT_ZIPKIN_TRACING_ENDPOINT` | container | Bus and tracing |

Service-specific: `photoapp.albums.limits.basic`.

## See also

- [../../../docs/DATABASE.md](../../../docs/DATABASE.md) — schema and seed data (597 albums)
- [../../../docs/ARCHITECTURE.md](../../../docs/ARCHITECTURE.md)
