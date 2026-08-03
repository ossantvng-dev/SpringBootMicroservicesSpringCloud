# photo-app-users-service

User registration, lookup, activation and role assignment.

**Port:** 8081 (not published — reached through the gateway)

## Swagger UI

- Through the gateway: http://localhost:8080/swagger-ui.html → select **users-service**
- Direct (native runs only): http://localhost:8081/swagger-ui.html

## Endpoints

| Method | Path | Access |
|---|---|---|
| `POST` | `/users` | **public** — registration |
| `GET` | `/users/username/{username}` | **public** |
| `GET` | `/users/{id}` | `USER` or `ADMIN` |
| `PUT` | `/users/{id}` | `USER` or `ADMIN` |
| `GET` | `/users` | `ADMIN` — paged, filterable |
| `GET` | `/users/email/{email}` | `ADMIN` |
| `GET` | `/users/{id}/active` | `ADMIN` — used by accounts-service over Feign |
| `PATCH` | `/users/{id}/activate` | `ADMIN` |
| `PATCH` | `/users/{id}/roles` | `ADMIN` |
| `DELETE` | `/users/{id}` | `ADMIN` |

Two layers apply: the path rules in `photo-app-security-lib` allow `USER` or `ADMIN` for
`/users/**`, and `@PreAuthorize` on individual methods narrows that further. The list and admin
operations are `ADMIN`-only by method annotation, not by path.

## Consumed by

**accounts-service**, via `UserFeignClient#isActive` → `GET /users/{id}/active`. That call is
wrapped in a Resilience4j circuit breaker and retry. It is the only synchronous inter-service
dependency in the system, and this service is on the receiving end of it — so it is the one
whose downtime affects another service.

## Depends on

| On | Why |
|---|---|
| MySQL | `users`, `roles`, `user_roles` |
| Config Server, Discovery, RabbitMQ, Zipkin | Standard for every service |
| `photo-app-commons` | DTOs, MapStruct mappers, `GlobalExceptionHandler` |
| `photo-app-entity-model-lib` | JPA entities |
| `photo-app-security-lib` | JWT filter and security chain |
| `photo-app-feign-lib` | Feign clients |
| `photo-app-tracing-lib` | Zipkin sender replacement |

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

- [../../../docs/DATABASE.md](../../../docs/DATABASE.md) — schema and seed data (100 users, 2 roles)
- [../../../docs/ARCHITECTURE.md](../../../docs/ARCHITECTURE.md) — security model
