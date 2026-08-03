# photo-app-photos-service

Photo management within albums. The leaf of the domain: user → account → album → photo.

**Port:** 8084 (not published — reached through the gateway)

## Swagger UI

- Through the gateway: http://localhost:8080/swagger-ui.html → select **photos-service**
- Direct (native runs only): http://localhost:8084/swagger-ui.html

## Access

Every endpoint requires a JWT with `USER` or `ADMIN`. There are no public paths.

## Scale note

This owns the largest table by row count — the seed alone is **5,970 photos**, ten per album.
It is the service where pagination and query shape matter most, and the one most likely to
expose an N+1 or a missing index. When investigating latency, check its `org.hibernate.SQL`
output first.

Keep `org.hibernate.orm.jdbc.bind` at WARN here. At TRACE it logs every JDBC parameter of every
statement, which on a paged photo listing is thousands of lines per request — see
[`../../../docs/LOGGING.md`](../../../docs/LOGGING.md).

## Depends on

| On | Why |
|---|---|
| MySQL | `photos` (FK to `albums`) |
| Config Server, Discovery, RabbitMQ, Zipkin | Standard for every service |
| `photo-app-commons`, `photo-app-entity-model-lib`, `photo-app-security-lib`, `photo-app-feign-lib`, `photo-app-tracing-lib` | Shared libraries |

Calls no other business service.

## Environment variables

| Variable | Required | Purpose |
|---|---|---|
| `CONFIG_SERVER_ADMIN_USER` / `CONFIG_SERVER_ADMIN_PASSWORD` | yes | Config Server auth |
| `CONFIG_SERVER_URL` | container | `http://photo-app-config-server:8888` |
| `SPRING_DATASOURCE_URL` | container | Points at `photo-app-mysql` |
| `EUREKA_CLIENT_SERVICEURL_DEFAULTZONE`, `EUREKA_INSTANCE_HOSTNAME` | container | Registration |
| `SPRING_RABBITMQ_HOST`, `MANAGEMENT_ZIPKIN_TRACING_ENDPOINT` | container | Bus and tracing |

## See also

- [../../../docs/DATABASE.md](../../../docs/DATABASE.md) — schema and seed data
- [../../../docs/LOGGING.md](../../../docs/LOGGING.md) — why the bind logger stays at WARN
